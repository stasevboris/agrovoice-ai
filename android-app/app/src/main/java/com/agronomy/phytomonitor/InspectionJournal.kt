package com.agronomy.phytomonitor

import android.content.Context
import com.agronomy.phytomonitor.journal.AchievementEntity
import com.agronomy.phytomonitor.journal.ArchiveContent
import com.agronomy.phytomonitor.journal.GeoPoint
import com.agronomy.phytomonitor.journal.JournalEventEntity
import com.agronomy.phytomonitor.journal.InspectionEntity
import com.agronomy.phytomonitor.journal.InspectionWithDetails
import com.agronomy.phytomonitor.journal.JournalArchive
import com.agronomy.phytomonitor.journal.JournalCsv
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.journal.JournalFormatException
import com.agronomy.phytomonitor.journal.JournalIssue
import com.agronomy.phytomonitor.journal.JournalRecords
import com.agronomy.phytomonitor.journal.JournalStorage
import com.agronomy.phytomonitor.journal.MAX_NOTE
import com.agronomy.phytomonitor.journal.MAX_PLOT
import com.agronomy.phytomonitor.journal.ParsedInspection
import com.agronomy.phytomonitor.journal.PlotEntity
import com.agronomy.phytomonitor.journal.REVIEWS
import com.agronomy.phytomonitor.journal.SyncQueueEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.zip.ZipFile

data class InspectionEntry(
    val id: String,
    val created: Long,
    val photo: String,
    val model: String,
    val note: String,
    val review: String,
    val detections: Int,
    val plot: String = "",
    val photoAvailable: Boolean = true,
    val row: String = "",
    val location: GeoPoint? = null
)

/** The result of a migration or an import: counts and every record that was not taken. */
data class JournalReport(
    val source: String,
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val withoutPhoto: Int = 0,
    val issues: List<JournalIssue> = emptyList()
) {
    val worthShowing: Boolean get() = added + updated > 0 || withoutPhoto > 0 || issues.isNotEmpty()

    fun summary(): String {
        val parts = mutableListOf("добавлено $added", "обновлено $updated", "без изменений $unchanged")
        if (withoutPhoto > 0) {
            parts += "без фото $withoutPhoto"
        }
        if (issues.isNotEmpty()) {
            parts += "не прочитано ${issues.size}"
        }
        return "$source: " + parts.joinToString(", ") + "."
    }

    fun toJson(): JSONObject {
        val problems = JSONArray()
        issues.forEach { problems.put(JSONObject().put("source", it.source).put("reason", it.reason)) }
        return JSONObject().put("source", source).put("added", added).put("updated", updated)
            .put("unchanged", unchanged).put("without_photo", withoutPhoto).put("issues", problems)
    }

    companion object {
        fun fromJson(data: JSONObject): JournalReport {
            val problems = data.optJSONArray("issues") ?: JSONArray()
            val issues = (0 until problems.length()).map { problems.getJSONObject(it) }
                .map { JournalIssue(it.optString("source"), it.optString("reason")) }
            return JournalReport(
                data.optString("source"), data.optInt("added"), data.optInt("updated"),
                data.optInt("unchanged"), data.optInt("without_photo"), issues
            )
        }
    }
}

/**
 * Explicit user-reviewed records, separate from automatically saved inference JSON.
 * Stored in Room (`journal.db`); the old JSON files in `inspections/` are migrated once and kept as they are.
 */
class InspectionJournal(
    context: Context,
    private val database: JournalDatabase = JournalDatabase.get(context),
    private val availableSpace: (File) -> Long = File::getUsableSpace,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Off by user setting: no badge rows are written, the journal itself is unaffected. */
    var achievementsEnabled: Boolean = true

    private val dao = database.journal()
    private val files = context.getExternalFilesDir(null) ?: context.filesDir
    private val legacyFolder = File(files, "inspections")
    private val migrationMarker = File(files, MIGRATION_REPORT)
    private val photoFolder = File(files, "photos")
    private val staging = File(context.cacheDir, "journal-import")

    /**
     * Saves the analysed result. A repeated save of the same report keeps the first coordinates:
     * they describe where the photo was taken, not where the note was edited.
     */
    fun save(
        report: File,
        note: String,
        review: String,
        plot: String = "",
        row: String? = null,
        location: GeoPoint? = null
    ): InspectionEntry {
        require(review in REVIEWS)
        require(note.length <= MAX_NOTE)
        require(plot.length <= MAX_PLOT)
        val prediction = JSONObject(report.readText())
        val previous = dao.inspectionByReport(report.name)
        val now = clock()
        val parsed = ParsedInspection(
            id = previous?.id ?: UUID.randomUUID().toString(),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            reportName = report.name,
            reportSha256 = sha256(report),
            prediction = prediction,
            note = note,
            review = review,
            plot = plot,
            row = row?.trim()?.ifEmpty { null },
            expertStatus = previous?.expertStatus ?: "none",
            archivePhoto = null,
            location = previous?.let(::storedLocation) ?: location
        )
        store(parsed, parsed.photoPath, operation = "upsert")
        return entry(requireNotNull(dao.inspectionDetails(parsed.id)))
    }

    fun list(): List<InspectionEntry> = dao.inspections().map(::entry)

    fun details(id: String): InspectionWithDetails? = dao.inspectionDetails(id)

    fun editNote(id: String, expectedUpdatedAt: Long, note: String): InspectionEntity =
        dao.editNote(id, expectedUpdatedAt, note, clock())

    /** The exported form of one inspection, or null. */
    fun record(id: String): JSONObject? = dao.inspectionDetails(id)?.let { JournalRecords.toJson(it) }

    fun export(): String {
        val records = JSONArray()
        dao.inspections().forEach { records.put(JournalRecords.toJson(it)) }
        return JournalRecords.document(records, containsPhotos = false, exportedAt = clock()).toString(2)
    }

    fun exportCsv(): String = JournalCsv.write(dao.inspections()) { File(it).isFile }

    /** Journal and photo files in one zip that [import] restores on another phone. Returns the photo count. */
    fun exportArchive(output: OutputStream): Int {
        val records = JSONArray()
        val photos = linkedMapOf<String, File>()
        dao.inspections().forEach { details ->
            val photo = details.photos.firstOrNull()?.photo?.localPath?.let(::File)
            val name = photo?.takeIf { it.isFile }?.let { JournalArchive.photoEntry(details.inspection.id, it) }
            if (name != null) {
                photos[name] = photo
            }
            records.put(JournalRecords.toJson(details, archivePhoto = name))
        }
        val journal = JournalRecords.document(records, containsPhotos = true, exportedAt = clock()).toString(2)
        JournalArchive.write(output, journal, photos)
        database.runInTransaction(Callable {
            dao.recordEvent(JournalEventEntity(JournalEventEntity.FULL_BACKUP, firstAt = clock()))
            recomputeAchievements()
        })
        return photos.size
    }

    /** Badges earned by the current journal; recomputed where records or backup facts change. */
    fun achievements(): List<AchievementEntity> = dao.achievements()

    private fun recomputeAchievements(): List<AchievementEntity> =
        if (achievementsEnabled) dao.recomputeAchievements({ File(it).isFile }, clock()) else emptyList()

    /** A journal JSON (v1 or v2) or a portable zip archive. Newer local versions are kept. */
    fun import(input: InputStream): JournalReport {
        val stream = BufferedInputStream(input)
        stream.mark(4)
        val zip = stream.read() == 'P'.code && stream.read() == 'K'.code
        stream.reset()
        if (!zip) {
            val text = try {
                JournalArchive.readDocument(stream)
            } catch (error: JournalFormatException) {
                return JournalReport(SOURCE_JSON, issues = listOf(JournalIssue("журнал", error.message.orEmpty())))
            }
            return importDocument(text, emptyMap(), emptyList(), SOURCE_JSON)
        }
        staging.deleteRecursively()
        try {
            val content: ArchiveContent = try {
                val archive = File(staging, "incoming.zip")
                JournalStorage.stageArchive(stream, archive, availableSpace)
                // ZipInputStream alone accepts an archive cut off after the last local entry.
                ZipFile(archive).use { it.size() }
                archive.inputStream().use { JournalArchive.read(it, staging, availableSpace) }
            } catch (error: JournalFormatException) {
                return JournalReport(SOURCE_ARCHIVE, issues = listOf(JournalIssue("архив", error.message ?: "архив не прочитан")))
            } catch (error: IOException) {
                // A truncated download ends the zip stream early: nothing is imported.
                return JournalReport(SOURCE_ARCHIVE, issues = listOf(JournalIssue("архив", error.message ?: "архив повреждён или обрезан")))
            }
            val journal = content.journal ?: return JournalReport(SOURCE_ARCHIVE, issues = content.issues)
            return importDocument(journal, content.photos, content.issues, SOURCE_ARCHIVE)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Moves the old JSON journal into Room once. Source files stay untouched; broken ones are listed in
     * the report with the reason. Returns null when the migration has already run.
     */
    fun migrateLegacy(): JournalReport? {
        if (migrationMarker.isFile) {
            return null
        }
        val sources = legacyFolder.listFiles()?.sortedBy { it.name }.orEmpty()
        val parsed = sources.filter { it.extension == "json" }.map { file ->
            val text = runCatching { file.readText() }.getOrNull()
            if (text == null) {
                Result.failure(JournalFormatException("файл не читается", file.name))
            } else {
                JournalRecords.parseText(text, file.name)
            }
        }
        val unfinished = sources.filter { it.extension == "tmp" }
            .map { JournalIssue(it.name, "незавершённая запись прошлой версии не перенесена") }
        val report = importRecords(parsed, emptyMap(), unfinished, SOURCE_LEGACY)
        migrationMarker.writeText(report.toJson().put("finished_epoch_ms", clock()).toString(2))
        return report
    }

    fun migrationReport(): JournalReport? = runCatching {
        JournalReport.fromJson(JSONObject(migrationMarker.readText()))
    }.getOrNull()

    /**
     * Used by tests to remove their own entries; the app has no delete action. The test inspection, its
     * pending sync rows and its now-unused «TEST:» plot go. Unrelated plots stay in place.
     */
    internal fun deleteForTest(id: String) {
        dao.deleteTestInspection(id, TEST_PREFIX)
    }

    private fun importDocument(text: String, photos: Map<String, File>, issues: List<JournalIssue>, source: String): JournalReport {
        val (records, problem) = JournalRecords.records(text)
        if (problem != null) {
            return JournalReport(source, issues = issues + problem)
        }
        JournalStorage.requireSpace(photoFolder, photos.values.sumOf { it.length() } + text.toByteArray(Charsets.UTF_8).size * 2L, availableSpace)
        return database.runInTransaction(Callable { importRecords(records, photos, issues, source) })
    }

    private fun importRecords(
        records: List<Result<ParsedInspection>>,
        photos: Map<String, File>,
        initialIssues: List<JournalIssue>,
        source: String
    ): JournalReport {
        val issues = initialIssues.toMutableList()
        var added = 0
        var updated = 0
        var unchanged = 0
        var withoutPhoto = 0
        for (result in records) {
            val parsed = result.getOrElse { error ->
                val problem = error as? JournalFormatException
                val place = problem?.source?.ifEmpty { null } ?: source
                issues += JournalIssue(place, problem?.reason ?: error.message ?: "запись не прочитана")
                continue
            }
            val existing = dao.inspection(parsed.id)
            val sameReport = dao.inspectionByReport(parsed.reportName)
            if (sameReport != null && sameReport.id != parsed.id) {
                issues += JournalIssue(parsed.id, "отчёт ${parsed.reportName} уже принадлежит другому осмотру")
                continue
            }
            val localPhoto = existing?.let { dao.inspectionDetails(it.id)?.photos?.firstOrNull()?.photo?.localPath }
                ?.takeIf { File(it).isFile }
            val newer = existing == null || parsed.updatedAt > existing.updatedAt
            if (!newer) {
                // The local record is as new or newer: keep it, only bring back a missing photo.
                if (localPhoto != null || parsed.archivePhoto == null) {
                    unchanged += 1
                    continue
                }
                val restored = restorePhoto(parsed, photos, null, issues)
                if (File(restored).isFile) {
                    dao.updatePhotoPath(parsed.id, restored)
                    updated += 1
                } else {
                    unchanged += 1
                    withoutPhoto += 1
                }
                continue
            }
            val photoPath = restorePhoto(parsed, photos, localPhoto, issues)
            if (!File(photoPath).isFile) {
                withoutPhoto += 1
            }
            store(parsed, photoPath, operation = "import")
            if (existing == null) {
                added += 1
            } else {
                updated += 1
            }
        }
        return JournalReport(source, added, updated, unchanged, withoutPhoto, issues)
    }

    /** Uses the archive photo only if its SHA-256 matches the analysed photo; never overwrites files. */
    private fun restorePhoto(
        parsed: ParsedInspection,
        photos: Map<String, File>,
        localPhoto: String?,
        issues: MutableList<JournalIssue>
    ): String {
        val original = File(parsed.photoPath)
        val fallback = localPhoto ?: original.path
        val name = parsed.archivePhoto ?: return fallback
        val staged = photos[name]
        if (staged == null) {
            issues += JournalIssue(parsed.id, "в архиве нет фото $name")
            return fallback
        }
        val digest = sha256(staged)
        if (parsed.photoSha256.isNotBlank() && digest != parsed.photoSha256) {
            issues += JournalIssue(parsed.id, "фото $name не совпадает с отчётом (SHA-256), не восстановлено")
            return fallback
        }
        if (localPhoto != null && sha256(File(localPhoto)) == digest) {
            return localPhoto
        }
        if (original.isFile && sha256(original) == digest) {
            return original.path
        }
        photoFolder.mkdirs()
        val target = File(photoFolder, "restored_${parsed.id}_${digest}.${staged.extension}")
        if (target.isFile && sha256(target) == digest) {
            return target.path
        }
        JournalStorage.copyPhoto(staged, target, availableSpace)
        return target.path
    }

    private fun store(parsed: ParsedInspection, photoPath: String, operation: String) {
        val record = JournalRecords.toRecord(parsed, plotId(parsed.plot), photoPath)
        val change = SyncQueueEntity(entity = "inspection", entityId = parsed.id, operation = operation, createdAt = clock())
        database.runInTransaction(Callable {
            dao.saveRecord(record, change)
            recomputeAchievements()
        })
    }

    private fun plotId(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        // Same plot whatever the letter case; SQLite NOCASE would fold only Latin letters.
        dao.plotsWithCounts().firstOrNull { it.plot.name.equals(trimmed, ignoreCase = true) }?.let { return it.plot.id }
        dao.insertPlot(PlotEntity(UUID.randomUUID().toString(), trimmed, updatedAt = clock()))
        return requireNotNull(dao.plotByName(trimmed)).id
    }

    private fun storedLocation(inspection: InspectionEntity): GeoPoint? {
        val lat = inspection.lat ?: return null
        val lon = inspection.lon ?: return null
        return GeoPoint(lat, lon, inspection.accuracyM ?: 0.0)
    }

    private fun entry(details: InspectionWithDetails): InspectionEntry {
        val inspection = details.inspection
        val photo = details.photos.firstOrNull()
        val path = photo?.photo?.localPath.orEmpty()
        return InspectionEntry(
            id = inspection.id,
            created = inspection.createdAt,
            photo = path,
            model = inspection.modelVersion,
            note = inspection.note,
            review = inspection.userReview,
            detections = photo?.detections?.size ?: 0,
            plot = details.plot?.name.orEmpty(),
            photoAvailable = path.isNotEmpty() && File(path).isFile,
            row = inspection.row.orEmpty(),
            location = storedLocation(inspection)
        )
    }

    companion object {
        const val MIGRATION_REPORT = "journal_migration_v1.json"
        const val TEST_PREFIX = "TEST:"
        const val SOURCE_LEGACY = "Перенос старого журнала"
        const val SOURCE_JSON = "Импорт JSON"
        const val SOURCE_ARCHIVE = "Восстановление из архива"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
