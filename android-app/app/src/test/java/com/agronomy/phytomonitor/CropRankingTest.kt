package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.ml.Letterbox
import com.agronomy.phytomonitor.ml.YoloPostprocessor
import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.*
import org.junit.Test

class CropRankingTest {
    private val output = arrayOf(floatArrayOf(208f), floatArrayOf(208f), floatArrayOf(100f), floatArrayOf(80f),
        floatArrayOf(0.95f), floatArrayOf(0.6f), floatArrayOf(0.4f))

    @Test
    fun manualCropRanksAvailableClassesInsteadOfDiscardingAnotherCropWinner() {
        assertEquals(0, YoloPostprocessor.decode(output, Letterbox(416, 416)).single().classId)
        val selected = YoloPostprocessor.decode(output, Letterbox(416, 416), allowedClasses = setOf(1, 2)).single()
        assertEquals(1, selected.classId)
        assertEquals(0.6f, selected.confidence, 0.0001f)
    }

    @Test
    fun manualChoiceCannotInflateAWeakClassAboveThreshold() {
        assertTrue(YoloPostprocessor.decode(output, Letterbox(416, 416), threshold = 0.65f, allowedClasses = setOf(1)).isEmpty())
        assertTrue(YoloPostprocessor.decode(output, Letterbox(416, 416), allowedClasses = emptySet()).isEmpty())
        assertTrue(YoloPostprocessor.decode(output, Letterbox(416, 416), allowedClasses = setOf(99)).isEmpty())
    }

    @Test
    fun aTopClassOnlyGraphExplicitlyRejectsUnsupportedRefinement() {
        val request = ActionRequest(VoiceAction.CROP, "potato", ActionSource.BUTTON)
        assertTrue(ActionPolicy.resolve(request, AppState(crops = listOf("potato"), canRefineCrop = false)) is Verdict.Reject)
    }
}
