package com.agronomy.phytomonitor.journal

import org.json.JSONArray
import org.json.JSONObject

/** Records in the exact shape the 0.18 app wrote to inspections/<id>.json. */
object JournalFixtures {
    fun prediction(photo: String, photoSha: String = "", labels: List<String> = listOf("Томат: фитофтороз")): JSONObject {
        val boxes = JSONArray()
        labels.forEachIndexed { index, label ->
            boxes.put(
                JSONObject()
                    .put("class_id", index)
                    .put("class_name", "Tomato_late_blight_$index")
                    .put("label_ru", label)
                    .put("confidence", 0.8 - index * 0.1)
                    .put("xyxy_pixels", JSONArray(listOf(10.0, 20.0, 110.0, 220.0)))
            )
        }
        return JSONObject()
            .put("created_epoch_ms", 1_760_000_000_000L)
            .put("photo", photo)
            .put("photo_sha256", photoSha)
            .put("decoded_width", 1536)
            .put("decoded_height", 2048)
            .put("model", "yolo26n")
            .put("model_sha256", "abc123")
            .put("detections", boxes)
    }

    fun legacyRecord(
        id: String,
        photo: String,
        photoSha: String = "",
        note: String = "Нижние листья",
        review: String = "incorrect",
        plot: String = "Теплица 2",
        updated: Long = 1_760_000_100_000L
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("created_epoch_ms", 1_760_000_000_000L)
        .put("updated_epoch_ms", updated)
        .put("report_name", "1760000000000_yolo26n_$id.json")
        .put("report_sha256", "def456")
        .put("prediction", prediction(photo, photoSha))
        .put("note", note)
        .put("review", review)
        .put("plot", plot)
        .put("review_scope", REVIEW_SCOPE)
}
