package com.agronomy.phytomonitor.ml

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A result owns this snapshot. Never reconstruct old provenance from the current model catalog. */
internal object ResultProvenanceSnapshot {
    fun create(model: DetectorModel, width: Int, height: Int, crop: String, threshold: Float,
               metadata: JSONObject): JSONObject {
        val geometry = Letterbox(width, height, model.side)
        val classes = JSONArray(model.classes)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(classes.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val preprocessing = JSONObject()
            .put("channels", "RGB").put("layout", "NCHW").put("dtype", "float32")
            .put("input_side", model.side).put("pixel_divisor", FieldDetector.PIXEL_DIVISOR.toDouble())
            .put("padding_value", FieldDetector.PADDING_VALUE).put("resize_filter", "Android FILTER_BITMAP_FLAG")
            .put("photo_decode", JSONObject()
                .put("max_side", PhotoFiles.MAX_DECODE_SIDE).put("sampling", "power_of_two_inSampleSize")
                .put("color_space", "sRGB").put("exif_orientation", "applied")
                .put("decoded_width", width).put("decoded_height", height))
            .put("letterbox", JSONObject()
                .put("scale", geometry.scale).put("resized_width", geometry.resizedWidth)
                .put("resized_height", geometry.resizedHeight).put("pad_x", geometry.padX).put("pad_y", geometry.padY))
        val detr = model.family == "detr"
        val classifier = model.classifier
        val nms = JSONObject().put("enabled", !detr)
        if (!detr) {
            nms.put("iou_threshold", FieldDetector.NMS_IOU.toDouble())
                .put("class_agnostic", classifier != null)
                .put("max_candidates", 30_000).put("max_detections", 300)
                .put("coordinate_space", "unclipped model input pixels")
        }
        val allowed = if (crop == "all") JSONObject.NULL else
            JSONArray(model.classCrops.indices.filter { model.classCrops[it] == crop })
        val postprocessing = JSONObject()
            .put("decoder", if (detr) "xyxy_score_class" else "cxcywh_class_scores")
            .put("nms", nms).put("confidence_threshold", threshold.toDouble())
            .put("proposal_threshold", if (!detr && classifier != null) FieldDetector.PROPOSAL_THRESHOLD.toDouble() else threshold.toDouble())
            .put("crop_filter", crop).put("allowed_class_ids", allowed)
            .put("crop_filter_stage", if (classifier == null) "detector" else "classifier")
            .put("crop_policy", if (detr && classifier == null) "filter_decoded_class" else "rank_within_crop_without_score_renormalization")
            .put("box_mapping", if (detr) "subtract_padding_divide_resized_axis_scale_clip" else "subtract_padding_divide_letterbox_scale_clip")
            .put("score", if (classifier == null) "detector_score_uncalibrated" else "detector_score_times_classifier_softmax_uncalibrated")
        val snapshot = JSONObject()
            .put("schema_version", 1).put("model_id", model.id).put("model_name", model.name)
            .put("model_sha256", model.sha256).put("family", model.family)
            .put("taxonomy_version", model.taxonomyVersion ?: "unspecified")
            .put("taxonomy_source", if (model.taxonomyVersion == null) "unspecified" else "manifest")
            .put("classes", classes).put("labels_ru", JSONArray(model.labels))
            .put("class_crops", JSONArray(model.classCrops)).put("class_order_sha256", fingerprint)
            .put("class_order_hash_encoding", "SHA-256 of UTF-8 compact JSON classes array; order significant")
            .put("preprocessing", preprocessing).put("postprocessing", postprocessing)
        if (classifier != null) {
            snapshot.put("classifier", JSONObject()
                .put("id", classifier.id).put("sha256", classifier.sha256).put("kind", classifier.kind)
                .put("input_side", classifier.side).put("max_classified_leaves", FieldDetector.MAX_CLASSIFIED_LEAVES)
                .put("margin_each_side", FieldDetector.LEAF_MARGIN.toDouble())
                .put("crop_rounding", "floor_left_top_ceil_right_bottom_clip")
                .put("square_padding_value", FieldDetector.PADDING_VALUE)
                .put("resize_filter", "Android createScaledBitmap filter=true")
                .put("channels", "RGB").put("layout", "NCHW").put("pixel_divisor", FieldDetector.PIXEL_DIVISOR.toDouble())
                .put("output", "logits_softmax_all_classes")
                .put("graph_normalization", metadata.optJSONObject("classifier")?.optString("normalization", "unspecified") ?: "unspecified"))
        }
        return snapshot
    }
}
