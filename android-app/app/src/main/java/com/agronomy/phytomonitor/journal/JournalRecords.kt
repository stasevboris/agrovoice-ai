package com.agronomy.phytomonitor.journal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

const val JOURNAL_SCHEMA = "agrovoice-inspections-v2"
const val LEGACY_JOURNAL_SCHEMA = "agrovoice-inspections-v1"
const val REVIEW_SCOPE = "User assessment of this entire photo/result, not verified leaf labels or training ground truth"
val REVIEWS = setOf("unrated", "correct", "incorrect", "uncertain")
const val MAX_NOTE = 4000
const val MAX_PLOT = 120

/** A record that cannot be read, with a reason a person can act on. Nothing is dropped silently. */
data class JournalIssue(val source: String, val reason: String)

/** [source] names the file or record; the message is «source: reason» when the source is known. */
/** Where the photo was taken: WGS84 degrees and the receiver's accuracy radius in metres. */
data class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Double)

class JournalFormatException(val reason: String, val source: String = "") :
    Exception(if (source.isEmpty()) reason else "$source: $reason")

/** One inspection as it is stored in inspections/ (v1) and in exports (v2). */
data class ParsedInspection(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val reportName: String,
    val reportSha256: String,
    val prediction: JSONObject,
    val note: String,
    val review: String,
    val plot: String,
    val row: String?,
    val expertStatus: String,
    val archivePhoto: String?,
    val location: GeoPoint? = null
) {
    val photoPath: String get() = prediction.getString("photo")
    val photoSha256: String get() = prediction.optString("photo_sha256")
    val modelId: String get() = prediction.getString("model")
    val detectionCount: Int get() = prediction.getJSONArray("detections").length()
}

/** Converts between the JSON record and Room entities. Pure Kotlin + org.json, tested on the JVM. */
object JournalRecords {
    fun parse(data: JSONObject): ParsedInspection {
        val id = requiredString(data, "id")
        val prediction = data.optJSONObject("prediction") ?: throw JournalFormatException("нет отчёта анализа (prediction)")
        requiredString(prediction, "photo", "в отчёте нет пути к фото")
        requiredString(prediction, "model", "в отчёте нет модели")
        if (prediction.optJSONArray("detections") == null) {
            throw JournalFormatException("в отчёте нет списка рамок")
        }
        val review = data.optString("review", "unrated")
        if (review !in REVIEWS) {
            throw JournalFormatException("неизвестная оценка «$review»")
        }
        val note = data.optString("note")
        val plot = data.optString("plot")
        if (note.length > MAX_NOTE || plot.length > MAX_PLOT) {
            throw JournalFormatException("заметка или участок длиннее допустимого")
        }
        val created = data.optLong("created_epoch_ms", 0L)
        if (created <= 0L) {
            throw JournalFormatException("нет даты осмотра")
        }
        return ParsedInspection(
            id = id,
            createdAt = created,
            updatedAt = data.optLong("updated_epoch_ms", created),
            reportName = requiredString(data, "report_name"),
            reportSha256 = data.optString("report_sha256"),
            prediction = prediction,
            note = note,
            review = review,
            plot = plot,
            row = data.optString("row").ifBlank { null },
            expertStatus = data.optString("expert_status", "none"),
            archivePhoto = data.optString("archive_photo").ifBlank { null },
            location = location(data.optJSONObject("location"))
        )
    }

    private fun location(data: JSONObject?): GeoPoint? {
        if (data == null) {
            return null
        }
        val point = GeoPoint(data.optDouble("lat"), data.optDouble("lon"), data.optDouble("accuracy_m"))
        val valid = point.lat in -90.0..90.0 && point.lon in -180.0..180.0 && point.accuracyM >= 0.0
        if (!valid) {
            throw JournalFormatException("координаты вне допустимого диапазона")
        }
        return point
    }

    /** Reads one file of the old journal; a broken file becomes an issue, not an exception. */
    fun parseText(text: String, source: String): Result<ParsedInspection> {
        val data = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (error: JSONException) {
            return Result.failure(JournalFormatException("файл повреждён или не является JSON", source))
        }
        return parseObject(data, source)
    }

    fun parseObject(data: JSONObject, source: String): Result<ParsedInspection> = try {
        Result.success(parse(data))
    } catch (error: JSONException) {
        Result.failure(JournalFormatException("неверный тип поля", source))
    } catch (error: JournalFormatException) {
        Result.failure(JournalFormatException(error.reason, source))
    }

    fun toRecord(parsed: ParsedInspection, plotId: String?, photoPath: String): InspectionRecord {
        val inspection = InspectionEntity(
            id = parsed.id,
            plotId = plotId,
            row = parsed.row,
            createdAt = parsed.createdAt,
            lat = parsed.location?.lat,
            lon = parsed.location?.lon,
            accuracyM = parsed.location?.accuracyM,
            note = parsed.note,
            userReview = parsed.review,
            expertStatus = parsed.expertStatus,
            modelVersion = parsed.modelId,
            modelSha256 = parsed.prediction.optString("model_sha256"),
            reportName = parsed.reportName,
            reportSha256 = parsed.reportSha256,
            predictionJson = parsed.prediction.toString(),
            updatedAt = parsed.updatedAt
        )
        val photo = PhotoEntity(
            id = parsed.id,
            inspectionId = parsed.id,
            localPath = photoPath,
            sha256 = parsed.photoSha256,
            width = parsed.prediction.optInt("decoded_width"),
            height = parsed.prediction.optInt("decoded_height")
        )
        return InspectionRecord(inspection, photo, detections(parsed.prediction, photo.id))
    }

    /** Boxes are in the coordinate space of the decoded photo, as written by PhotoFiles.saveResult. */
    fun detections(prediction: JSONObject, photoId: String): List<DetectionEntity> {
        val boxes = prediction.getJSONArray("detections")
        return (0 until boxes.length()).map { index ->
            val box = boxes.getJSONObject(index)
            val xyxy = box.optJSONArray("xyxy_pixels") ?: JSONArray(listOf(0, 0, 0, 0))
            DetectionEntity(
                photoId = photoId,
                classCode = box.optString("class_name"),
                labelRu = box.optString("label_ru"),
                confidence = box.optDouble("confidence", 0.0),
                x1 = xyxy.optDouble(0, 0.0),
                y1 = xyxy.optDouble(1, 0.0),
                x2 = xyxy.optDouble(2, 0.0),
                y2 = xyxy.optDouble(3, 0.0)
            )
        }
    }

    /** The exported record keeps the v1 fields, so an old reader still finds them. */
    fun toJson(details: InspectionWithDetails, archivePhoto: String? = null): JSONObject {
        val inspection = details.inspection
        val photo = details.photos.firstOrNull()?.photo
        val record = JSONObject()
            .put("id", inspection.id)
            .put("created_epoch_ms", inspection.createdAt)
            .put("updated_epoch_ms", inspection.updatedAt)
            .put("report_name", inspection.reportName)
            .put("report_sha256", inspection.reportSha256)
            .put("prediction", JSONObject(inspection.predictionJson))
            .put("note", inspection.note)
            .put("review", inspection.userReview)
            .put("plot", details.plot?.name ?: "")
            .put("row", inspection.row ?: "")
            .put("expert_status", inspection.expertStatus)
            .put("review_scope", REVIEW_SCOPE)
            .put("photo_path", photo?.localPath ?: "")
            .put("photo_sha256", photo?.sha256 ?: "")
        val lat = inspection.lat
        val lon = inspection.lon
        if (lat != null && lon != null) {
            record.put("location", JSONObject().put("lat", lat).put("lon", lon).put("accuracy_m", inspection.accuracyM ?: 0.0))
        }
        if (archivePhoto != null) {
            record.put("archive_photo", archivePhoto)
        }
        return record
    }

    fun document(records: JSONArray, containsPhotos: Boolean, exportedAt: Long): JSONObject = JSONObject()
        .put("schema", JOURNAL_SCHEMA)
        .put("exported_epoch_ms", exportedAt)
        .put("contains_photo_bytes", containsPhotos)
        .put("records", records)

    /** Records of an exported document; v1 and v2 share the record shape. */
    fun records(document: String): Pair<List<Result<ParsedInspection>>, JournalIssue?> {
        val data = try {
            JSONObject(document.removePrefix("\uFEFF"))
        } catch (error: JSONException) {
            return emptyList<Result<ParsedInspection>>() to JournalIssue("journal.json", "файл повреждён или не является JSON")
        }
        val schema = data.optString("schema")
        if (schema != JOURNAL_SCHEMA && schema != LEGACY_JOURNAL_SCHEMA) {
            return emptyList<Result<ParsedInspection>>() to JournalIssue("journal.json", "неизвестный формат «$schema»")
        }
        val items = data.optJSONArray("records") ?: JSONArray()
        val parsed = (0 until items.length()).map { index ->
            val source = "запись ${index + 1}"
            val item = items.optJSONObject(index)
            if (item == null) {
                Result.failure(JournalFormatException("запись не является объектом", source))
            } else {
                parseObject(item, source)
            }
        }
        return parsed to null
    }

    private fun requiredString(data: JSONObject, key: String, reason: String = "нет поля $key"): String {
        val value = data.optString(key)
        if (value.isBlank()) {
            throw JournalFormatException(reason)
        }
        return value
    }
}
