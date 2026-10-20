package com.agronomy.phytomonitor.draft

import com.agronomy.phytomonitor.PhotoQuality
import com.agronomy.phytomonitor.PhotoVerdict
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ml.ModelCatalog
import org.json.JSONObject
import org.json.JSONArray
import com.agronomy.phytomonitor.journal.FrameRole
import com.agronomy.phytomonitor.journal.MAX_INSPECTION_FRAMES
import java.util.UUID

/** One unfinished inspection. The original report and catalog never follow later model updates. */
data class InspectionDraft(
    val id: String = UUID.randomUUID().toString(),
    val photo: String,
    val reportName: String? = null,
    val reportJson: String? = null,
    val catalogJson: String? = null,
    val note: String = "",
    val review: String = "unrated",
    val plot: String = "",
    val row: String = "",
    val plant: String = "",
    val model: String = "",
    val crop: String = "all",
    val threshold: Float = 0.25f,
    val saveRequested: Boolean = false,
    val savedBeforeVersion: Long? = null,
    val frames: List<DraftFrame> = emptyList(),
    val activeFrameId: String = id,
    val addingFrame: Boolean = false
) {
    /** Legacy fields describe the selected frame; the ordered list owns the series identity. */
    fun allFrames(): List<DraftFrame> = frames.ifEmpty {
        listOf(DraftFrame(id, photo, reportName = reportName, reportJson = reportJson, catalogJson = catalogJson))
    }

    val primaryReportName: String? get() = allFrames().first().reportName

    fun selectFrame(id: String): InspectionDraft {
        val frame = allFrames().first { it.id == id }
        return copy(photo = frame.photo, reportName = frame.reportName, reportJson = frame.reportJson,
            catalogJson = frame.catalogJson, activeFrameId = id, frames = allFrames())
    }

    fun updateFrame(frame: DraftFrame): InspectionDraft {
        require(frame.id == activeFrameId)
        return copy(frames = allFrames().map { if (it.id == frame.id) frame else it })
            .selectFrame(frame.id)
    }

    fun appendFrame(photo: String): InspectionDraft {
        require(allFrames().size < MAX_INSPECTION_FRAMES)
        val frame = DraftFrame(UUID.randomUUID().toString(), photo)
        return copy(frames = allFrames() + frame, addingFrame = false).selectFrame(frame.id)
    }

    fun saveCompleted(currentVersion: Long?): Boolean = saveRequested && currentVersion != null &&
        (savedBeforeVersion == null || currentVersion != savedBeforeVersion)

    fun result(): DetectionResult? {
        val report = reportJson?.let(::JSONObject) ?: return null
        val catalog = JSONObject(requireNotNull(catalogJson))
        val originalModel = ModelCatalog.parse(catalog).first { it.id == report.getString("model") }
        require(originalModel.sha256 == report.getString("model_sha256")) { "Модель черновика не соответствует результату" }
        val detections = report.getJSONArray("detections")
        val boxes = List(detections.length()) { index ->
            val item = detections.getJSONObject(index)
            val classId = item.getInt("class_id")
            require(classId in originalModel.classes.indices)
            val coordinates = item.getJSONArray("xyxy_pixels")
            LeafDetection(classId, item.getDouble("confidence").toFloat(),
                coordinates.getDouble(0).toFloat(), coordinates.getDouble(1).toFloat(),
                coordinates.getDouble(2).toFloat(), coordinates.getDouble(3).toFloat())
        }
        val quality = report.optJSONObject("photo_quality")?.let {
            PhotoQuality(PhotoVerdict.valueOf(it.getString("verdict").uppercase(java.util.Locale.ROOT)),
                if (it.isNull("laplacian_variance")) null else it.getDouble("laplacian_variance"),
                it.getDouble("mean_luma"), it.getDouble("overexposed_share"),
                it.getInt("sample_width"), it.getInt("sample_height"))
        }
        return DetectionResult(originalModel, boxes, report.getDouble("inference_ms"),
            report.getDouble("pipeline_ms"), report.optInt("proposed_leaves"),
            report.optInt("classified_leaves"), report.optInt("skipped_leaves"), quality)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", if (frames.isEmpty()) 1 else 2).put("id", id).put("photo", photo)
        .put("report_name", reportName).put("report", reportJson).put("catalog", catalogJson)
        .put("note", note).put("review", review).put("plot", plot).put("row", row).put("plant", plant)
        .put("model", model).put("crop", crop).put("threshold", threshold.toDouble())
        .put("save_requested", saveRequested).put("saved_before_version", savedBeforeVersion)
        .also { json ->
            if (frames.isNotEmpty()) {
                json.put("frames", JSONArray(frames.map { it.toJson() }))
                    .put("active_frame_id", activeFrameId).put("adding_frame", addingFrame)
            }
        }

    companion object {
        fun fromJson(json: JSONObject): InspectionDraft {
            val schema = json.getInt("schema")
            require(schema in 1..2) { "Неизвестный формат черновика" }
            val frames = if (schema == 2) json.getJSONArray("frames").let { array ->
                List(array.length()) { DraftFrame.fromJson(array.getJSONObject(it)) }
            } else emptyList()
            val active = if (schema == 2) json.getString("active_frame_id") else json.getString("id")
            if (schema == 2) {
                require(frames.size in 1..MAX_INSPECTION_FRAMES)
                require(frames.all { it.id.isNotBlank() } && frames.map { it.id }.distinct().size == frames.size)
                require(frames.any { it.id == active })
            }
            val draft = InspectionDraft(
                id = json.getString("id"), photo = json.getString("photo"),
                reportName = json.optionalString("report_name"), reportJson = json.optionalString("report"),
                catalogJson = json.optionalString("catalog"), note = json.getString("note"),
                review = json.getString("review"), plot = json.getString("plot"), row = json.getString("row"),
                plant = json.getString("plant"), model = json.getString("model"), crop = json.getString("crop"),
                threshold = json.getDouble("threshold").toFloat(), saveRequested = json.getBoolean("save_requested"),
                savedBeforeVersion = if (json.isNull("saved_before_version")) null else json.getLong("saved_before_version"),
                frames = frames, activeFrameId = active, addingFrame = json.optBoolean("adding_frame")
            )
            return if (frames.isEmpty()) draft else draft.selectFrame(active)
        }

        private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
    }
}

data class DraftFrame(
    val id: String,
    val photo: String,
    val role: FrameRole = FrameRole.UNSPECIFIED,
    val reportName: String? = null,
    val reportJson: String? = null,
    val catalogJson: String? = null
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("photo", photo).put("role", role.value)
        .put("report_name", reportName).put("report", reportJson).put("catalog", catalogJson)

    companion object {
        fun fromJson(json: JSONObject) = DraftFrame(json.getString("id"), json.getString("photo"),
            FrameRole.from(json.getString("role")), json.optional("report_name"),
            json.optional("report"), json.optional("catalog"))

        private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
