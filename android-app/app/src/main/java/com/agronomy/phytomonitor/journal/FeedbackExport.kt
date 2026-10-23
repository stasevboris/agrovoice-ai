package com.agronomy.phytomonitor.journal

import android.graphics.Bitmap
import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.ml.PhotoFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deliberately separate from backup: never serialize an inspection or recursively copy unknown JSON. */
object FeedbackExport {
    private val predictionFields = "decoded_width decoded_height model model_sha256 family crop_filter confidence_threshold app_version inference_ms pipeline_ms proposed_leaves classified_leaves skipped_leaves other_crop_top"
    private val provenanceFields = "schema_version model_id model_name model_sha256 family taxonomy_version taxonomy_source class_order_sha256 class_order_hash_encoding"

    private fun scalars(source: JSONObject, fields: String): JSONObject = JSONObject().apply {
        fields.split(' ').forEach { key ->
            val value = source.opt(key)
            if (value is String || value is Number || value is Boolean) put(key, value)
        }
    }

    private fun numericArray(value: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until value.length()) (value.opt(index) as? Number)?.let { put(it) }
    }

    fun manifestFrame(frame: PhotoWithDetections, exportedSha: String, image: String): JSONObject {
        val photo = frame.photo
        val raw = photo.predictionJson?.let(::JSONObject)
        val prediction = raw?.let { scalars(it, predictionFields) }
        if (raw != null && prediction != null) {
            val boxes = raw.optJSONArray("detections") ?: JSONArray()
            prediction.put("detections", JSONArray((0 until boxes.length()).map { index ->
                val box = boxes.getJSONObject(index)
                scalars(box, "class_id class_name label_ru confidence").apply {
                    box.optJSONArray("xyxy_pixels")?.let { put("xyxy_pixels", numericArray(it)) }
                }
            }))
            raw.optJSONObject("result_provenance")?.let { original ->
                val provenance = scalars(original, provenanceFields)
                listOf("classes", "labels_ru", "class_crops").forEach { key ->
                    original.optJSONArray(key)?.let { values ->
                        provenance.put(key, JSONArray((0 until values.length()).mapNotNull { values.opt(it) as? String }))
                    }
                }
                val specs = mapOf(
                    "preprocessing" to "channels layout dtype input_side pixel_divisor padding_value resize_filter",
                    "postprocessing" to "decoder confidence_threshold proposal_threshold crop_filter crop_filter_stage crop_policy box_mapping score",
                    "classifier" to "id sha256 kind input_side max_classified_leaves margin_each_side crop_rounding square_padding_value resize_filter channels layout pixel_divisor output graph_normalization"
                )
                specs.forEach { (key, fields) -> original.optJSONObject(key)?.let { provenance.put(key, scalars(it, fields)) } }
                val pre = original.optJSONObject("preprocessing")
                listOf("photo_decode" to "max_side sampling color_space exif_orientation decoded_width decoded_height",
                    "letterbox" to "scale resized_width resized_height pad_x pad_y").forEach { (key, fields) ->
                    pre?.optJSONObject(key)?.let { provenance.getJSONObject("preprocessing").put(key, scalars(it, fields)) }
                }
                original.optJSONObject("postprocessing")?.let { post ->
                    post.optJSONObject("nms")?.let { provenance.getJSONObject("postprocessing").put("nms",
                        scalars(it, "enabled iou_threshold class_agnostic max_candidates max_detections coordinate_space")) }
                    post.optJSONArray("allowed_class_ids")?.let { provenance.getJSONObject("postprocessing").put("allowed_class_ids", numericArray(it)) }
                }
                prediction.put("result_provenance", provenance)
            }
        }
        return JSONObject().put("image", image).put("original_photo_sha256", photo.sha256)
            .put("export_photo_sha256", exportedSha).put("original_report_sha256", photo.reportSha256)
            .put("prediction", prediction ?: JSONObject.NULL)
            // The time of the opinion is personal metadata and is not needed to learn from the frame.
            .put("feedback", frame.feedback?.toJson()?.apply { remove("updated_at") } ?: JSONObject.NULL)
    }

    fun write(frames: List<PhotoWithDetections>, output: OutputStream, allowedPhoto: (String) -> File?): Int {
        require(frames.isNotEmpty() && frames.size <= MAX_INSPECTION_FRAMES && frames.map { it.photo.id }.distinct().size == frames.size) { "Выбери кадры для экспорта." }
        val records = JSONArray()
        ZipOutputStream(output).use { zip ->
            frames.forEachIndexed { index, frame ->
                val file = requireNotNull(allowedPhoto(frame.photo.localPath)) { "Фото недоступно в хранилище приложения." }
                require(frame.photo.sha256.isNotBlank() && InspectionJournal.sha256(file) == frame.photo.sha256) { "Исходное фото изменилось или его SHA неизвестен." }
                val bitmap = PhotoFiles.decode(file)
                val bytes = try {
                    val raw = frame.photo.predictionJson?.let(::JSONObject)
                    require(raw != null && raw.optInt("decoded_width") == bitmap.width && raw.optInt("decoded_height") == bitmap.height) {
                        "Не удалось подтвердить координаты анализа. Экспорт кадра отменён."
                    }
                    ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                } finally { bitmap.recycle() }
                val name = "frames/${index + 1}.png"
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                records.put(manifestFrame(frame, sha, name))
            }
            val manifest = JSONObject().put("schema", "agrovoice.feedback.v1")
                .put("image_encoding", "PNG without EXIF; EXIF orientation applied; decoded analysis coordinates preserved")
                .put("hashes", "original_photo_sha256 identifies source bytes; export_photo_sha256 identifies re-encoded PNG; original_report_sha256 identifies the unmodified private report, not this filtered prediction")
                .put("label_source", "user opinion, not expert verified").put("frames", records)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return frames.size
    }
}
