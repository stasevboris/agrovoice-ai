package com.agronomy.phytomonitor.ml

import com.agronomy.phytomonitor.PhotoQuality
import com.agronomy.phytomonitor.assessAnalysis
import org.json.JSONObject
import java.util.Locale

/** Additive fields: existing reports and inspection export remain readable. */
internal fun addAnalysisAssessment(report: JSONObject, detectionCount: Int, quality: PhotoQuality?) {
    report.put("assessment", JSONObject()
        .put("version", 1)
        .put("status", assessAnalysis(detectionCount, quality).name.lowercase(Locale.ROOT))
        .put("policy", "Advisory only; no calibrated diagnostic acceptance or rejection"))
    report.put("photo_quality", quality?.let {
        JSONObject()
            .put("method", "whole_frame_luma_laplacian_v1")
            .put("calibrated", false)
            .put("blocking", false)
            .put("verdict", it.verdict.name.lowercase(Locale.ROOT))
            .put("sample_width", it.sampleWidth)
            .put("sample_height", it.sampleHeight)
            .put("laplacian_variance", it.sharpness ?: JSONObject.NULL)
            .put("mean_luma", it.brightness)
            .put("overexposed_share", it.overexposedShare)
    } ?: JSONObject.NULL)
}
