package com.agronomy.phytomonitor.journal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

const val JOURNAL_SCHEMA = "agrovoice-inspections-v3"
const val PREVIOUS_JOURNAL_SCHEMA = "agrovoice-inspections-v2"
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

/** One inspection from legacy records or the frame-preserving v3 archive. */
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
    val plant: String = "",
    val location: GeoPoint? = null,
    val plantId: String? = null,
    val frames: List<ParsedFrame> = emptyList()
) {
    val photoPath: String get() = prediction.getString("photo")
    val photoSha256: String get() = prediction.optString("photo_sha256")
    val modelId: String get() = prediction.getString("model")
    val detectionCount: Int get() = prediction.getJSONArray("detections").length()

    fun allFrames(): List<ParsedFrame> = frames.ifEmpty { listOf(ParsedFrame(
        id, 0, FrameRole.UNSPECIFIED, photoPath, photoSha256,
        prediction.optInt("decoded_width"), prediction.optInt("decoded_height"), reportName,
        reportSha256, prediction.toString(), plant, plantId, archivePhoto
    )) }
}

/** Converts between the JSON record and Room entities. Pure Kotlin + org.json, tested on the JVM. */
object JournalRecords {
    /** Member order and the JSON representation of a number do not change saved evidence. */
    internal fun sameJson(first: Any?, second: Any?): Boolean = when {
        first is JSONObject && second is JSONObject -> {
            val keys = first.keys().asSequence().toSet()
            keys == second.keys().asSequence().toSet() && keys.all { sameJson(first.opt(it), second.opt(it)) }
        }
        first is JSONArray && second is JSONArray -> first.length() == second.length() &&
            (0 until first.length()).all { sameJson(first.opt(it), second.opt(it)) }
        first is Number && second is Number -> first.toString().toBigDecimal().compareTo(second.toString().toBigDecimal()) == 0
        else -> first == second
    }

    /** The logical legacy ID is preserved in Room/JSON, never interpreted as a filesystem path. */
    fun photoFileId(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

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
        if (note.length > MAX_NOTE || plot.length > MAX_PLOT || data.optString("plant").length > MAX_PLOT) {
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
            plant = data.optString("plant").trim(),
            plantId = data.optString("plant_id").takeIf { it.isNotBlank() && it != "null" }?.also {
                if (it.length > 120 || data.optString("plant").isBlank()) throw JournalFormatException("неверная ссылка на растение")
            },
            location = location(data.optJSONObject("location")),
            frames = parseFrames(data)
        )
    }

    private fun parseFrames(data: JSONObject): List<ParsedFrame> {
        if (!data.has("frames")) return emptyList()
        val values = data.optJSONArray("frames") ?: throw JournalFormatException("неверный список кадров")
        if (values.length() !in 1..MAX_INSPECTION_FRAMES) throw JournalFormatException("нужно от 1 до $MAX_INSPECTION_FRAMES кадров")
        val frames = (0 until values.length()).map { index ->
            val frame = values.getJSONObject(index)
            val id = requiredString(frame, "id")
            val numericOrder = (frame.opt("order") as? Number)?.toDouble()
            if (numericOrder == null || !numericOrder.isFinite() || numericOrder % 1.0 != 0.0 || numericOrder !in 0.0..<MAX_INSPECTION_FRAMES.toDouble()) {
                throw JournalFormatException("неверный порядок кадров")
            }
            val order = numericOrder.toInt()
            val role = FrameRole.from(requiredString(frame, "role"))
            val sha = frame.optString("photo_sha256")
            if (sha.isNotBlank() && !Regex("[0-9a-f]{64}").matches(sha)) throw JournalFormatException("неверный SHA-256 кадра")
            val raw = if (frame.isNull("prediction_json")) null else frame.getString("prediction_json")
            if (raw != null) {
                val prediction = JSONObject(raw)
                requiredString(prediction, "model")
                if (prediction.optJSONArray("detections") == null) throw JournalFormatException("нет списка рамок кадра")
                val savedSha = prediction.optString("photo_sha256")
                if (savedSha.isNotBlank()) {
                    if (!Regex("[0-9a-f]{64}").matches(savedSha) || sha != savedSha) {
                        throw JournalFormatException("SHA-256 кадра не соответствует исходному анализу")
                    }
                }
            }
            val stored = frame.optJSONArray("stored_detections")
            if (frame.has("stored_detections") && stored == null || raw != null && stored != null) {
                throw JournalFormatException("неверные сохранённые рамки кадра")
            }
            if (stored != null) detections(JSONObject().put("detections", stored), id)
            val plant = frame.optString("plant").trim()
            val plantId = frame.optString("plant_id").takeIf { it.isNotBlank() && it != "null" }
            if (plant.length > MAX_PLOT || plantId != null && (plantId.length > 120 || plant.isBlank())) {
                throw JournalFormatException("неверная ссылка кадра на растение")
            }
            ParsedFrame(id, order, role, frame.optString("photo_path"), sha,
                frame.optInt("width"), frame.optInt("height"), frame.optString("report_name"),
                frame.optString("report_sha256"), raw, plant, plantId,
                frame.optString("archive_photo").takeIf { it.isNotBlank() && it != "null" }, stored?.toString())
        }
        if (frames.map { it.id }.distinct().size != frames.size || frames.map { it.order }.sorted() != frames.indices.toList()) {
            throw JournalFormatException("повторяющиеся ID или неверный порядок кадров")
        }
        return frames.sortedBy { it.order }
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

    fun toRecord(parsed: ParsedInspection, plotId: String?, plantId: String?, photoPath: String): InspectionRecord {
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
        val frame = frameRecord(parsed.id, parsed.allFrames().first(), plantId, photoPath)
        return InspectionRecord(inspection, frame.photo, frame.detections)
    }

    fun frameRecord(inspectionId: String, frame: ParsedFrame, plantId: String?, path: String): FrameRecord {
        val photo = PhotoEntity(
            id = frame.id,
            inspectionId = inspectionId,
            plantId = plantId,
            localPath = path,
            sha256 = frame.photoSha256,
            width = frame.width,
            height = frame.height,
            frameOrder = frame.order,
            frameRole = frame.role.value,
            reportName = frame.reportName,
            reportSha256 = frame.reportSha256,
            predictionJson = frame.predictionJson
        )
        val prediction = frame.prediction ?: frame.storedDetectionsJson?.let {
            JSONObject().put("detections", JSONArray(it))
        }
        return FrameRecord(photo, prediction?.let { detections(it, photo.id) }.orEmpty())
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
    fun toJson(details: InspectionWithDetails, archivePhoto: String? = null,
               archivePhotos: Map<String, String> = emptyMap(), photoHashes: Map<String, String> = emptyMap()): JSONObject {
        val inspection = details.inspection
        val ordered = details.orderedPhotos()
        val photo = ordered.firstOrNull()?.photo
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
            .put("plant", ordered.firstOrNull()?.plantName ?: "")
            .put("plant_id", photo?.plantId ?: "")
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
        val frames = JSONArray()
        ordered.forEachIndexed { index, detail ->
            val item = detail.photo
            val primary = item.id == inspection.id || ordered.size == 1
            val raw = item.predictionJson ?: inspection.predictionJson.takeIf { primary }
            val name = archivePhotos[item.id] ?: archivePhoto.takeIf { index == 0 }
            val frame = JSONObject().put("id", item.id).put("order", index).put("role", item.frameRole)
                .put("photo_path", item.localPath).put("photo_sha256", photoHashes[item.id] ?: item.sha256)
                .put("width", item.width).put("height", item.height)
                .put("report_name", item.reportName.ifBlank { if (primary) inspection.reportName else "" })
                .put("report_sha256", item.reportSha256.ifBlank { if (primary) inspection.reportSha256 else "" })
                .put("prediction_json", raw ?: JSONObject.NULL)
                .put("plant", detail.plantName ?: "").put("plant_id", item.plantId ?: "")
            if (name != null) frame.put("archive_photo", name)
            if (raw == null && detail.detections.isNotEmpty()) {
                frame.put("stored_detections", JSONArray(detail.detections.map { detection ->
                    JSONObject().put("class_name", detection.classCode).put("label_ru", detection.labelRu)
                        .put("confidence", detection.confidence)
                        .put("xyxy_pixels", JSONArray(listOf(detection.x1, detection.y1, detection.x2, detection.y2)))
                }))
            }
            frames.put(frame)
        }
        record.put("frames", frames)
        return record
    }

    fun document(records: JSONArray, containsPhotos: Boolean, exportedAt: Long): JSONObject = JSONObject()
        .put("schema", JOURNAL_SCHEMA)
        .put("exported_epoch_ms", exportedAt)
        .put("contains_photo_bytes", containsPhotos)
        .put("records", records)

    /** Legacy v1/v2 records become one frame; v3 can carry every frame independently. */
    fun records(document: String): Pair<List<Result<ParsedInspection>>, JournalIssue?> {
        val data = try {
            JSONObject(document.removePrefix("\uFEFF"))
        } catch (error: JSONException) {
            return emptyList<Result<ParsedInspection>>() to JournalIssue("journal.json", "файл повреждён или не является JSON")
        }
        val schema = data.optString("schema")
        if (schema !in setOf(JOURNAL_SCHEMA, PREVIOUS_JOURNAL_SCHEMA, LEGACY_JOURNAL_SCHEMA)) {
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
        val ids = parsed.mapNotNull { it.getOrNull()?.id }
        if (ids.distinct().size != ids.size) return emptyList<Result<ParsedInspection>>() to
            JournalIssue("journal.json", "повторяющиеся ID осмотров")
        return parsed to null
    }

    private fun requiredString(data: JSONObject, key: String, reason: String = "нет поля $key"): String {
        val raw = data.opt(key)
        val value = raw as? String ?: throw JournalFormatException(
            if (raw == null || raw == JSONObject.NULL) reason else "неверный тип поля $key"
        )
        if (value.isBlank()) {
            throw JournalFormatException(reason)
        }
        return value
    }
}
