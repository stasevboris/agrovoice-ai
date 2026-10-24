package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.ml.CropVerifierGraph
import com.agronomy.phytomonitor.ml.VerificationSummary
import com.agronomy.phytomonitor.ml.Letterbox
import com.agronomy.phytomonitor.ml.LeafDetection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CropVerifierTest {
    private fun metadata() = JSONObject().put("id", "verifier").put("asset", "models/verifier.onnx")
        .put("sha256", "a".repeat(64)).put("side", 224)
        .put("crops", JSONArray(listOf("apple", "tomato", "__reject__")))
        .put("policy", "crop_vs_reject_v1").put("threshold", 0.9958845687447002)
        .put("detector_threshold", 0.25)

    @Test
    fun keepsDetectorCropWithoutForcingVerifierArgmax() {
        val graph = CropVerifierGraph.parse(metadata(), listOf("apple", "tomato"))
        val logits = floatArrayOf(10f, 11f, 0f)
        assertTrue(graph.accepts(logits, "apple"))
        assertEquals(1.0 / (1.0 + kotlin.math.exp(-10.0)), graph.score(logits, "apple"), 1e-12)
        assertFalse(graph.accepts(floatArrayOf(0f, 2f, 10f), "apple"))
    }

    @Test
    fun exactThresholdAndExtremeLogitsAreHandled() {
        val graph = CropVerifierGraph.parse(metadata().put("threshold", 0.5), listOf("apple", "tomato"))
        assertTrue(graph.accepts(floatArrayOf(0f, 100f, 0f), "apple"))
        assertEquals(1.0, graph.score(floatArrayOf(10000f, 0f, -10000f), "apple"), 0.0)
        assertEquals(0.0, graph.score(floatArrayOf(-10000f, 0f, 10000f), "apple"), 0.0)
    }

    @Test
    fun invalidGraphOrOutputCannotSilentlyAcceptLeaf() {
        val invalid = listOf(metadata().put("threshold", 1.1), metadata().put("policy", "unknown"),
            metadata().put("side", 416), metadata().put("asset", "../secret.onnx"),
            metadata().put("crops", JSONArray(listOf("tomato", "__reject__"))),
            metadata().put("max_leaves", 0), metadata().put("max_leaves", 100))
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CropVerifierGraph.parse(value, listOf("apple", "tomato"))
            }
        }
        val graph = CropVerifierGraph.parse(metadata(), listOf("apple", "tomato"))
        for (values in listOf(floatArrayOf(0f), floatArrayOf(Float.NaN, 0f, 1f))) {
            assertThrows(IllegalArgumentException::class.java) { graph.score(values, "apple") }
        }
    }

    @Test
    fun summaryRoundTripKeepsRejectionsAndUnprocessedLeavesSeparate() {
        val summary = VerificationSummary(5, 3, 2, 2, 1, emptyList())
        val restored = VerificationSummary.fromJson(JSONObject(summary.toJson().toString()))
        assertEquals(summary, restored)
        assertEquals(2, restored.skipped)
        assertEquals(2, restored.rejected)
    }

    @Test
    fun manualCropNeverPromotesLosingClassInVerifierMode() {
        val graph = CropVerifierGraph.parse(metadata(), listOf("apple", "tomato"))
        val output = arrayOf(floatArrayOf(100f), floatArrayOf(100f), floatArrayOf(50f),
            floatArrayOf(50f), floatArrayOf(0.9f), floatArrayOf(0.3f))
        assertEquals(0, graph.decode(output, Letterbox(416, 416), listOf("apple", "tomato"), "tomato").size)
        assertEquals(0, graph.decode(output, Letterbox(416, 416), listOf("apple", "tomato"), "all").single().classId)
    }

    @Test
    fun skippedLeavesAreNeverDiagnosedAndAcceptedLeavesStayUnchanged() {
        val graph = CropVerifierGraph.parse(metadata().put("max_leaves", 2), listOf("apple", "tomato"))
        val leaves = List(3) { index -> LeafDetection(0, .8f, index.toFloat(), 0f, 10f, 10f) }
        var calls = 0
        val (accepted, summary) = graph.verify(leaves, listOf("apple", "tomato")) { index, _ ->
            calls++
            if (index == 0) floatArrayOf(10f, 0f, 0f) else floatArrayOf(0f, 0f, 10f)
        }
        assertEquals(2, calls)
        assertEquals(listOf(leaves[0]), accepted)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.rejected)
        assertEquals(summary, VerificationSummary.fromJson(summary.toJson()))
    }
}
