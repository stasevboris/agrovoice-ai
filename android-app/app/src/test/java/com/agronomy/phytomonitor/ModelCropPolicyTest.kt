package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.ml.DetectorModel
import com.agronomy.phytomonitor.ml.ModelCropPolicy
import org.junit.Assert.*
import org.junit.Test

class ModelCropPolicyTest {
    private val bundled = DetectorModel("bundled", "Bundled", "models/bundled.onnx", "a".repeat(64),
        classes = listOf("Apple leaf"), labels = listOf("Яблоня"), crops = listOf("apple"))

    @Test fun automaticFallbackPreservesUnsupportedCropAndRequiresClarification() {
        val crop = ModelCropPolicy.restore("orange", bundled, automaticFallback = true)
        assertEquals("orange", crop)
        val error = assertThrows(IllegalArgumentException::class.java) { ModelCropPolicy.requireSupported(bundled, crop) }
        assertTrue(error.message!!.contains("Апельсин"))
        val canonical = bundled.copy(crops = listOf("apple", "orange"))
        ModelCropPolicy.requireSupported(canonical, crop)
    }

    @Test fun normalStartupAndSupportedCropKeepExistingPolicy() {
        assertEquals("all", ModelCropPolicy.restore("orange", bundled, automaticFallback = false))
        assertEquals("apple", ModelCropPolicy.restore("apple", bundled, automaticFallback = true))
        ModelCropPolicy.requireSupported(bundled, "apple")
        ModelCropPolicy.requireSupported(bundled, "all")
    }

    @Test fun detrCropRulesRemainUnchanged() {
        val detr = bundled.copy(family = "detr")
        assertEquals("all", ModelCropPolicy.restore("apple", detr, automaticFallback = true))
        ModelCropPolicy.requireSupported(detr, "orange")
    }
}
