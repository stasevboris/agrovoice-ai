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
import com.agronomy.phytomonitor.journal.PlantEntity
import com.agronomy.phytomonitor.journal.PlotEntity
import com.agronomy.phytomonitor.journal.REVIEWS
import com.agronomy.phytomonitor.journal.SyncQueueEntity
import com.agronomy.phytomonitor.journal.InspectionFrame
import com.agronomy.phytomonitor.journal.FrameRole
import com.agronomy.phytomonitor.journal.ParsedFrame
import com.agronomy.phytomonitor.journal.PhotoEntity
import com.agronomy.phytomonitor.journal.MAX_INSPECTION_FRAMES
import com.agronomy.phytomonitor.journal.orderedPhotos
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
    val plant: String = "",
    val photoAvailable: Boolean = true,
    val row: String = "",
    val location: GeoPoint? = null,
    val plantId: String? = null
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
        location: GeoPoint? = null,
        plant: String = ""
    ): InspectionEntry {
        val previous = dao.inspectionByReport(report.name)
        val id = previous?.id ?: UUID.randomUUID().toString()
        return saveSeries(id, listOf(InspectionFrame(id, report)), note, review, plot, row, location, plant)
    }

    /** Append analysed frames atomically; the first report remains the inspection identity. */
    fun saveSeries(
        inspectionId: String,
        frames: List<InspectionFrame>,
        note: String,
        review: String,
        plot: String = "",
        row: String? = null,
        location: GeoPoint? = null,
        plant: String = ""
    ): InspectionEntry {
        require(inspectionId.isNotBlank())
        require(frames.size in 1..MAX_INSPECTION_FRAMES) { "В осмотре нужно от 1 до $MAX_INSPECTION_FRAMES кадров." }
        require(frames.all { it.id.isNotBlank() } && frames.map { it.id }.distinct().size == frames.size) { "ID кадров должны быть непустыми и разными." }
        require(frames.map { it.report.name }.distinct().size == frames.size) { "Один результат анализа нельзя добавить в серию дважды." }
        require(review in REVIEWS)
        require(note.length <= MAX_NOTE)
        require(plot.length <= MAX_PLOT)
        require(plant.length <= MAX_PLOT)
        val analysed = frames.mapIndexed { index, frame ->
            val raw = frame.report.readText()
            val prediction = JSONObject(raw)
            require(prediction.optJSONArray("detections") != null && prediction.optString("model").isNotBlank()) { "Нет корректного результата анализа кадра." }
            val photo = ownedPhoto(prediction.getString("photo")) ?: throw IOException("Фото кадра недоступно. Восстановите его перед сохранением серии.")
            val digest = sha256(photo)
            require(prediction.optString("photo_sha256").let { it.isBlank() || it == digest }) { "Фото кадра изменилось после анализа." }
            ParsedFrame(frame.id, index, frame.role, photo.path, digest,
                prediction.optInt("decoded_width"), prediction.optInt("decoded_height"), frame.report.name,
                sha256(frame.report), raw, plant.trim())
        }
        return database.runInTransaction(Callable {
            val previous = dao.inspectionByReport(analysed.first().reportName) ?: dao.inspection(inspectionId)
            val id = previous?.id ?: inspectionId
            val old = previous?.let { dao.inspectionDetails(it.id) }
            require(previous == null || previous.reportName == analysed.first().reportName) { "Первый результат осмотра нельзя заменить. Сохраните новый осмотр." }
            val firstId = old?.orderedPhotos()?.firstOrNull()?.photo?.id ?: analysed.first().id
            val adjusted = analysed.mapIndexed { index, frame -> if (index == 0) frame.copy(id = firstId) else frame }
            require(adjusted.map { it.id }.distinct().size == adjusted.size) { "Повторяющийся ID кадра." }
            require(old == null || old.photos.all { photo -> adjusted.any { it.id == photo.photo.id } }) { "Существующие кадры нельзя удалить сохранением неполной серии." }
            for (frame in adjusted) checkFrameIdentity(id, frame)
            val plotRef = plotId(plot)
            val plantRef = plantId(plotRef, plant)
            val canonicalPlant = plantRef?.let { dao.plant(it)?.name }.orEmpty()
            val resolved = adjusted.map { it.copy(plantId = plantRef, plant = canonicalPlant) }
            val sameFrames = old != null && old.photos.size == resolved.size && resolved.all { frame ->
                old.photos.firstOrNull { it.photo.id == frame.id }?.photo?.let { photo ->
                    photo.frameOrder == frame.order && photo.frameRole == frame.role.value && photo.plantId == plantRef &&
                        photo.localPath == frame.photoPath && photo.sha256 == frame.photoSha256 && photo.reportSha256 == frame.reportSha256
                } == true
            }
            val normalizedRow = row?.trim()?.ifEmpty { null }
            if (previous != null && sameFrames && previous.note == note && previous.userReview == review &&
                previous.plotId == plotRef && previous.row == normalizedRow) return@Callable entry(requireNotNull(old))
            val now = clock()
            val primary = resolved.first()
            val expertStatus = if (previous != null && !sameFrames) "unreviewed" else previous?.expertStatus ?: "none"
            val parsed = ParsedInspection(id, previous?.createdAt ?: now, maxOf(now, (previous?.updatedAt ?: 0L) + 1),
                primary.reportName, primary.reportSha256, JSONObject(requireNotNull(primary.predictionJson)), note, review,
                plot, normalizedRow, expertStatus, null, plant.trim(),
                previous?.let(::storedLocation) ?: location, plantRef, resolved)
            store(parsed, resolved.associate { it.id to it.photoPath }, "upsert")
            entry(requireNotNull(dao.inspectionDetails(id)))
        })
    }

    fun list(): List<InspectionEntry> = dao.inspections().map(::entry)

    fun details(id: String): InspectionWithDetails? = dao.inspectionDetails(id)?.let { it.copy(photos = it.orderedPhotos()) }

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
            if (details.photos.isEmpty()) throw IOException("У осмотра ${details.inspection.id} нет кадров. Полный архив не создан.")
            val names = mutableMapOf<String, String>()
            val hashes = mutableMapOf<String, String>()
            for (frame in details.orderedPhotos()) {
                val photo = ownedPhoto(frame.photo.localPath)
                    ?: throw IOException("Фото кадра ${frame.photo.id} недоступно. Полный архив не создан; восстановите фото или экспортируйте JSON.")
                val digest = sha256(photo)
                if (frame.photo.sha256.isNotBlank() && frame.photo.sha256 != digest) throw IOException("Фото кадра ${frame.photo.id} изменилось (SHA-256). Полный архив не создан.")
                val name = JournalArchive.photoEntry(JournalRecords.photoFileId(frame.photo.id), photo)
                if (!JournalArchive.isPhotoEntry(name)) throw IOException("Неподдерживаемый формат фото кадра ${frame.photo.id}. Полный архив не создан.")
                names[frame.photo.id] = name
                hashes[frame.photo.id] = digest
                photos[name] = photo
            }
            records.put(JournalRecords.toJson(details, archivePhotos = names, photoHashes = hashes))
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

    /** A journal JSON (v1/v2/v3) or portable archive. Newer local versions are kept. */
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
            val local = existing?.let { dao.inspectionDetails(it.id) }
            val frames = parsed.allFrames()
            val newer = existing == null || parsed.updatedAt > existing.updatedAt
            try {
                checkPrimaryEvidence(parsed)
                if (existing != null) checkInspectionIdentity(existing, parsed)
                for (frame in frames) checkFrameIdentity(parsed.id, frame)
                if (newer && local != null) require(local.photos.all { old -> frames.any { it.id == old.photo.id } }) {
                    "Импорт не содержит всех существующих кадров осмотра; локальная серия сохранена."
                }
            } catch (error: IllegalArgumentException) {
                issues += JournalIssue(parsed.id, error.message ?: "кадры не совпадают с существующим осмотром")
                continue
            }
            if (!newer) {
                var restoredAny = false
                var missing = false
                for (frame in frames) {
                    val old = local?.photos?.firstOrNull { it.photo.id == frame.id }?.photo ?: continue
                    if (ownedPhoto(old.localPath) != null) continue
                    val restored = restorePhoto(frame, photos, null, issues)
                    if (restored.isNotBlank() && File(restored).isFile) {
                        dao.updateFramePhotoPath(frame.id, restored)
                        restoredAny = true
                    } else missing = true
                }
                if (restoredAny) updated += 1 else unchanged += 1
                if (missing) withoutPhoto += 1
            } else {
                val paths = frames.associate { frame ->
                    val oldPath = local?.photos?.firstOrNull { it.photo.id == frame.id }?.photo?.localPath
                    frame.id to restorePhoto(frame, photos, oldPath, issues)
                }
                if (paths.values.any { it.isBlank() || !File(it).isFile }) withoutPhoto += 1
                store(parsed, paths, operation = "import")
                if (existing == null) added += 1 else updated += 1
            }
        }
        return JournalReport(source, added, updated, unchanged, withoutPhoto, issues)
    }

    /** Uses the archive photo only if its SHA-256 matches the analysed photo; never overwrites files. */
    private fun restorePhoto(
        parsed: ParsedFrame,
        photos: Map<String, File>,
        localPhoto: String?,
        issues: MutableList<JournalIssue>
    ): String {
        fun matching(path: String): File? {
            val file = ownedPhoto(path) ?: return null
            if (parsed.photoSha256.isBlank() || sha256(file) == parsed.photoSha256) return file
            issues += JournalIssue(parsed.id, "локальное фото не совпадает с отчётом (SHA-256)")
            return null
        }
        val original = matching(parsed.photoPath)
        val local = localPhoto?.let(::matching)
        val fallback = local?.path ?: original?.path.orEmpty()
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
        if (local != null && sha256(local) == digest) {
            return local.path
        }
        if (original != null && sha256(original) == digest) {
            return original.path
        }
        photoFolder.mkdirs()
        val target = File(photoFolder, "restored_${JournalRecords.photoFileId(parsed.id)}_${digest}.${staged.extension}")
        val existingTarget = ownedPhoto(target.path)
        if (existingTarget != null && sha256(existingTarget) == digest) {
            return existingTarget.path
        }
        JournalStorage.copyPhoto(staged, target, availableSpace)
        return target.path
    }

    /** JSON paths are untrusted. Resolve containment before inspecting or reading a file. */
    private fun ownedPhoto(path: String): File? {
        if (path.isBlank()) return null
        return runCatching {
            val file = File(path).canonicalFile
            val root = photoFolder.canonicalFile
            file.takeIf { it.toPath().startsWith(root.toPath()) && it != root && it.isFile }
        }.getOrNull()
    }

    private fun checkFrameIdentity(inspectionId: String, frame: ParsedFrame) {
        val previous = dao.photo(frame.id) ?: return
        require(previous.inspectionId == inspectionId) { "ID кадра уже принадлежит другому осмотру." }
        require(previous.sha256.isBlank() || previous.sha256 == frame.photoSha256) {
            "Содержимое существующего кадра нельзя заменить. Сохраните новый результат отдельно."
        }
        require(previous.reportName.isBlank() || previous.reportName == frame.reportName) { "Исходный отчёт кадра нельзя заменить." }
        require(previous.reportSha256.isBlank() || previous.reportSha256 == frame.reportSha256) {
            "SHA исходного отчёта кадра изменился."
        }
        if (previous.predictionJson != null) require(frame.predictionJson != null &&
            JournalRecords.sameJson(JSONObject(previous.predictionJson), JSONObject(frame.predictionJson))) {
            "Исходный анализ кадра нельзя изменить; повторный анализ сохраняется отдельно."
        }
        require(previous.predictionJson != null || frame.predictionJson == null || dao.detectionCount(frame.id) == 0) {
            "У кадра уже есть сохранённые рамки без исходного отчёта. Новый анализ сохраните отдельным кадром."
        }
    }

    private fun checkInspectionIdentity(previous: InspectionEntity, incoming: ParsedInspection) {
        require(previous.reportName == incoming.reportName &&
            (previous.reportSha256.isBlank() || previous.reportSha256 == incoming.reportSha256) &&
            JournalRecords.sameJson(JSONObject(previous.predictionJson), incoming.prediction)) {
            "Исходный отчёт осмотра нельзя заменить импортом. Повторный анализ сохраните отдельно."
        }
    }

    private fun checkPrimaryEvidence(parsed: ParsedInspection) {
        val primary = parsed.frames.firstOrNull()?.prediction ?: return
        fun analysis(prediction: JSONObject): JSONObject = JSONObject(prediction.toString()).apply {
            // Downloaded primary reports retain their analysis but use local paths and sync provenance.
            listOf("photo", "photo_path", "photo_sha256", "agrovoice_frames", "agrovoice_origin", "agrovoice_sync_source")
                .forEach(::remove)
        }
        require(JournalRecords.sameJson(analysis(parsed.prediction), analysis(primary))) {
            "Исходный анализ осмотра не совпадает с анализом первого кадра. Запись не импортирована."
        }
    }

    private fun store(parsed: ParsedInspection, paths: Map<String, String>, operation: String) {
        val change = SyncQueueEntity(entity = "inspection", entityId = parsed.id, operation = operation, createdAt = clock())
        database.runInTransaction(Callable {
            val plotRef = plotId(parsed.plot)
            val frames = parsed.allFrames()
            val plants = frames.associate { it.id to plantId(plotRef, it.plant, it.plantId) }
            val first = frames.first()
            val record = JournalRecords.toRecord(parsed, plotRef, plants[first.id], paths[first.id].orEmpty())
            val additional = frames.drop(1).map { frame ->
                JournalRecords.frameRecord(parsed.id, frame, plants[frame.id], paths[frame.id].orEmpty())
            }
            dao.saveRecord(record.copy(additionalFrames = additional), change)
            recomputeAchievements()
        })
    }

    private fun plantId(plotRef: String?, name: String, importedId: String? = null): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (importedId != null) {
            val existing = dao.plant(importedId)
            require(existing == null || existing.plotId == plotRef && existing.name == trimmed) {
                "ID растения относится к другому участку или имени. Импорт остановлен."
            }
            if (existing == null) dao.insertPlant(PlantEntity(importedId, plotRef, trimmed, clock(), clock()))
            return importedId
        }
        val spoken = com.agronomy.phytomonitor.voice.VoiceCommands.spokenPlot(trimmed)
        val matches = dao.plantsInPlot(plotRef).filter {
            com.agronomy.phytomonitor.voice.VoiceCommands.spokenPlot(it.name) == spoken
        }
        require(matches.size <= 1) { "Название растения неоднозначно. Укажи другое имя." }
        matches.singleOrNull()?.let { return it.id }
        dao.insertPlant(PlantEntity(UUID.randomUUID().toString(), plotRef, trimmed, createdAt = clock(), updatedAt = clock()))
        return requireNotNull(dao.plantByName(plotRef, trimmed)).id
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
        val photo = details.orderedPhotos().firstOrNull()
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
            plant = photo?.plantName.orEmpty(),
            photoAvailable = path.isNotEmpty() && File(path).isFile,
            row = inspection.row.orEmpty(),
            location = storedLocation(inspection),
            plantId = photo?.photo?.plantId
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
