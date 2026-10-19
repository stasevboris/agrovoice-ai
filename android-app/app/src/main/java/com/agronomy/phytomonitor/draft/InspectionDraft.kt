package com.agronomy.phytomonitor.draft

import com.agronomy.phytomonitor.PhotoQuality
import com.agronomy.phytomonitor.PhotoVerdict
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ml.ModelCatalog
import org.json.JSONObject
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
    val savedBeforeVersion: Long? = null
) {
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
        .put("schema", 1).put("id", id).put("photo", photo)
        .put("report_name", reportName).put("report", reportJson).put("catalog", catalogJson)
        .put("note", note).put("review", review).put("plot", plot).put("row", row).put("plant", plant)
        .put("model", model).put("crop", crop).put("threshold", threshold.toDouble())
        .put("save_requested", saveRequested).put("saved_before_version", savedBeforeVersion)

    companion object {
        fun fromJson(json: JSONObject): InspectionDraft {
            require(json.getInt("schema") == 1) { "Неизвестный формат черновика" }
            return InspectionDraft(
                id = json.getString("id"), photo = json.getString("photo"),
                reportName = json.optionalString("report_name"), reportJson = json.optionalString("report"),
                catalogJson = json.optionalString("catalog"), note = json.getString("note"),
                review = json.getString("review"), plot = json.getString("plot"), row = json.getString("row"),
                plant = json.getString("plant"), model = json.getString("model"), crop = json.getString("crop"),
                threshold = json.getDouble("threshold").toFloat(), saveRequested = json.getBoolean("save_requested"),
                savedBeforeVersion = if (json.isNull("saved_before_version")) null else json.getLong("saved_before_version")
            )
        }

        private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
