package com.agronomy.phytomonitor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultProvenanceDisplayTest {
    private fun details(provenance: JSONObject): List<String> = savedProvenanceInfo(
        JSONObject().put("model", "historical-model").put("result_provenance", provenance).toString()
    ).details

    @Test
    fun partialSnapshotShowsUnknownParametersInsteadOfZeroNanOrDisabledNms() {
        val fixtures = listOf(
            JSONObject(),
            JSONObject().put("schema_version", 1).put("preprocessing", JSONObject()).put("postprocessing", JSONObject())
        )
        fixtures.forEach { fixture ->
            val lines = details(fixture)
            assertTrue(lines.toString(), lines.any { it == "Классов: не указано" })
            assertTrue(lines.toString(), lines.any { it.contains("Вход модели: не указан") })
            assertTrue(lines.toString(), lines.any { it.contains("Порог отбора: не указан") })
            assertTrue(lines.toString(), lines.any { it == "Применение NMS: неизвестно." })
            assertFalse(lines.toString(), lines.any { "NaN" in it || "0 × 0" in it || "не применялось" in it })
        }
    }

    @Test
    fun wrongNmsTypesRemainUnknownAndExplicitBooleansRemainDistinct() {
        listOf(JSONObject.NULL, "false", "true", 0, 1, JSONObject()).forEach { invalid ->
            val lines = details(JSONObject().put("postprocessing", JSONObject()
                .put("nms", JSONObject().put("enabled", invalid))))
            assertTrue("$invalid: $lines", "Применение NMS: неизвестно." in lines)
        }
        val absent = details(JSONObject().put("postprocessing", JSONObject().put("nms", JSONObject())))
        assertTrue("Применение NMS: неизвестно." in absent)
        val disabled = details(JSONObject().put("postprocessing", JSONObject()
            .put("nms", JSONObject().put("enabled", false))))
        assertTrue("Дополнительное NMS в приложении не применялось." in disabled)
        val enabled = details(JSONObject().put("postprocessing", JSONObject()
            .put("nms", JSONObject().put("enabled", true).put("iou_threshold", 0.7))))
        assertTrue(enabled.toString(), enabled.any { "NMS, IoU 0.70" in it })
    }

    @Test
    fun invalidNumbersAreNotRenderedAsMeasuredProcessingParameters() {
        listOf(JSONObject.NULL, "0.5", "NaN", "Infinity", -0.1, 1.1, true, JSONObject()).forEach { invalid ->
            val lines = details(JSONObject().put("postprocessing", JSONObject()
                .put("confidence_threshold", invalid)
                .put("nms", JSONObject().put("enabled", true).put("iou_threshold", invalid))))
            assertTrue("$invalid: $lines", lines.any { it.contains("Порог отбора: не указан") })
            assertTrue("$invalid: $lines", lines.any { it.contains("NMS, IoU неизвестен") })
        }
        for (valid in listOf(0, 0.35, 1)) {
            val lines = details(JSONObject().put("postprocessing", JSONObject().put("confidence_threshold", valid)))
            assertFalse("$valid: $lines", lines.any { it.contains("Порог отбора: не указан") })
        }
    }

    @Test
    fun invalidDimensionsAndClassListsAreUnknown() {
        listOf(JSONObject.NULL, "416", 0, -416, 0.5, 416.5, 1e40, true).forEach { invalid ->
            val lines = details(JSONObject().put("preprocessing", JSONObject().put("input_side", invalid)))
            assertTrue("$invalid: $lines", lines.any { it.contains("Вход модели: не указан") })
        }
        for (invalid in listOf(JSONObject.NULL, 0, JSONArray().put(5), JSONArray().put(""))) {
            assertTrue(details(JSONObject().put("classes", invalid)).contains("Классов: не указано"))
        }
        val valid = details(JSONObject().put("classes", JSONArray().put("apple__healthy"))
            .put("preprocessing", JSONObject().put("input_side", 416).put("channels", "RGB")))
        assertTrue(valid.contains("Классов: 1"))
        assertTrue(valid.any { it.contains("416 × 416, RGB") })
    }
}
