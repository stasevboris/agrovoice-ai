package com.agronomy.phytomonitor.journal

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A table for Excel and LibreOffice in a Russian locale: «;» separates columns, UTF-8 with BOM keeps
 * Cyrillic readable. Labels come from the saved report; confidence is not a disease probability.
 */
object JournalCsv {
    val header = listOf(
        "id", "дата", "участок", "ряд", "оценка", "экспертиза", "модель", "рамок",
        "первая метка", "уверенность первой", "все метки", "заметка", "фото", "фото есть",
        "широта", "долгота", "точность, м"
    )

    fun write(rows: List<InspectionWithDetails>, photoExists: (String) -> Boolean): String {
        val lines = mutableListOf(header.joinToString(";") { cell(it) })
        rows.forEach { lines += line(it, photoExists).joinToString(";") { value -> cell(value) } }
        return "\uFEFF" + lines.joinToString("\r\n") + "\r\n"
    }

    private fun line(details: InspectionWithDetails, photoExists: (String) -> Boolean): List<String> {
        val inspection = details.inspection
        val photo = details.photos.firstOrNull()
        val detections = photo?.detections.orEmpty().sortedByDescending { it.confidence }
        val first = detections.firstOrNull()
        return listOf(
            inspection.id,
            date(inspection.createdAt),
            details.plot?.name.orEmpty(),
            inspection.row.orEmpty(),
            inspection.userReview,
            inspection.expertStatus,
            inspection.modelVersion,
            detections.size.toString(),
            first?.labelRu.orEmpty(),
            first?.let { String.format(Locale.ROOT, "%.3f", it.confidence) }.orEmpty(),
            detections.joinToString(", ") { it.labelRu },
            inspection.note,
            photo?.photo?.localPath.orEmpty(),
            if (photo != null && photoExists(photo.photo.localPath)) "да" else "нет",
            inspection.lat?.let { String.format(Locale.ROOT, "%.6f", it) }.orEmpty(),
            inspection.lon?.let { String.format(Locale.ROOT, "%.6f", it) }.orEmpty(),
            inspection.accuracyM?.let { String.format(Locale.ROOT, "%.0f", it) }.orEmpty()
        )
    }

    /** RFC 4180 quoting; a leading formula sign is neutralised so a note cannot run in a spreadsheet. */
    fun cell(value: String): String {
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        val needsQuotes = safe.any { it == ';' || it == '"' || it == '\n' || it == '\r' || it == ',' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun date(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(epochMs))
    }
}

/** What a portable archive contained. Photos are staged in a temporary folder until the import decides. */
data class ArchiveContent(val journal: String?, val photos: Map<String, File>, val issues: List<JournalIssue>)

/**
 * A portable journal: `journal.json` plus `photos/<inspection id>.<ext>`. Only these names are read
 * back, so an archive cannot write outside the staging folder.
 */
object JournalArchive {
    const val JOURNAL_ENTRY = "journal.json"
    const val MAX_PHOTO_BYTES = 64L * 1024 * 1024
    const val MAX_JOURNAL_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 20000
    private val photoName = Regex("photos/[A-Za-z0-9_-]{1,80}\\.(jpg|jpeg|png|webp|heic)")

    fun photoEntry(inspectionId: String, photo: File): String {
        val extension = photo.extension.lowercase().ifBlank { "jpg" }
        return "photos/$inspectionId.$extension"
    }

    fun isPhotoEntry(name: String): Boolean = photoName.matches(name)

    fun write(output: OutputStream, journal: String, photos: Map<String, File>) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(JOURNAL_ENTRY))
            zip.write(journal.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photos.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, staging: File): ArchiveContent {
        staging.mkdirs()
        var journal: String? = null
        val photos = mutableMapOf<String, File>()
        val issues = mutableListOf<JournalIssue>()
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRIES) {
                    issues += JournalIssue("архив", "слишком много файлов, остальные пропущены")
                    break
                }
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == JOURNAL_ENTRY -> journal = String(readLimited(zip, MAX_JOURNAL_BYTES, name), Charsets.UTF_8)
                    isPhotoEntry(name) -> photos[name] = stage(zip, name, staging, issues) ?: continue
                    else -> issues += JournalIssue(name, "посторонний файл в архиве пропущен")
                }
            }
        }
        if (journal == null) {
            issues += JournalIssue("архив", "нет файла journal.json")
        }
        return ArchiveContent(journal, photos, issues)
    }

    private fun stage(zip: ZipInputStream, name: String, staging: File, issues: MutableList<JournalIssue>): File? {
        val target = File(staging, name.substringAfter('/'))
        try {
            target.outputStream().use { output -> copyLimited(zip, output, MAX_PHOTO_BYTES) }
            return target
        } catch (error: JournalFormatException) {
            target.delete()
            issues += JournalIssue(name, error.reason)
            return null
        }
    }

    private fun readLimited(input: InputStream, limit: Long, name: String): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        try {
            copyLimited(input, buffer, limit)
        } catch (error: JournalFormatException) {
            throw JournalFormatException(error.reason, name)
        }
        return buffer.toByteArray()
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) {
                return
            }
            total += read
            if (total > limit) {
                throw JournalFormatException("файл больше ${limit / (1024 * 1024)} МБ")
            }
            output.write(chunk, 0, read)
        }
    }
}
