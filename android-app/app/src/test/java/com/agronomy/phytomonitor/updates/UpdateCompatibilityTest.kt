package com.agronomy.phytomonitor.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateCompatibilityTest {
    private val canonical = listOf("apple__healthy", "apple__scab")
    private fun release(classes: List<String> = canonical): UpdateRelease {
        val sha = "a".repeat(64)
        val model = JSONObject().put("id", "test_allcrops").put("name", "Test")
            .put("asset", "models/test.onnx").put("sha256", sha).put("family", "yolo")
            .put("dataset", "allcrops_v1").put("side", 416)
            .put("classes", JSONArray(classes)).put("labels_ru", JSONArray(listOf("Healthy", "Scab")))
        return UpdateRelease.parse(JSONObject().put("id", "model-test").put("kind", "model")
            .put("version", "test").put("file_name", "model.onnx").put("bytes", 100)
            .put("sha256", sha).put("download_path", "/updates/model-test/download")
            .put("metadata", JSONObject().put("prepared_by", "prepare_model_release.py").put("manifest_entry", model)))
    }

    @Test fun canonicalOrderIsPreservedAndChangedOrderIsRejected() {
        val model = UpdateCompatibility.model(release(), emptyList(), canonical)
        assertEquals(canonical, model.classes)
        assertEquals("taxonomy_v1", model.taxonomyVersion)
        assertThrows(IllegalArgumentException::class.java) {
            UpdateCompatibility.model(release(canonical.reversed()), emptyList(), canonical)
        }
    }

    @Test fun oneToOneAndClassifierOutputsAreRejected() {
        assertTrue(UpdateCompatibility.supportedOutput(longArrayOf(1, 6, 3549), 2))
        assertFalse(UpdateCompatibility.supportedOutput(longArrayOf(1, 300, 6), 2))
        assertFalse(UpdateCompatibility.supportedOutput(longArrayOf(1, 2), 2))
        val item = release()
        item.metadata.getJSONObject("manifest_entry").put("family", "detr")
        assertThrows(IllegalArgumentException::class.java) {
            UpdateCompatibility.model(item, emptyList(), canonical)
        }
    }

    @Test fun updateCannotSilentlyRemoveOrIntroduceAnExperimentalVerifier() {
        val item = release()
        val original = UpdateCompatibility.model(item, emptyList(), canonical)
        val data = JSONObject().put("id", "v").put("asset", "models/v.onnx").put("sha256", "b".repeat(64))
            .put("side", 224).put("crops", JSONArray(listOf("apple", "__reject__")))
            .put("policy", "crop_vs_reject_v1").put("threshold", 0.99).put("detector_threshold", 0.25)
        item.metadata.getJSONObject("manifest_entry").put("verifier", data)
        assertThrows(IllegalArgumentException::class.java) { UpdateCompatibility.model(item, emptyList(), canonical) }
        val graph = com.agronomy.phytomonitor.ml.CropVerifierGraph.parse(data, listOf("apple"))
        assertThrows(IllegalArgumentException::class.java) {
            UpdateCompatibility.model(release(), listOf(original.copy(verifier = graph)), canonical)
        }
    }
}
