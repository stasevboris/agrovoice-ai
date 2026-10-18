package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.ml.addAnalysisAssessment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalysisAssessmentTest {
    private val dark = analysePhoto(IntArray(16), 4, 4)

    @Test fun noDetectionsNeverMeansHealthy() {
        assertEquals(AnalysisAssessment.NO_DETECTIONS, assessAnalysis(0, dark))
        val speech = spokenAnalysis(emptyList(), dark)
        assertTrue(speech.startsWith(dark.advice!!))
        assertTrue(speech.contains("Это не означает, что растение здорово"))
        assertTrue(speech.contains("культуре и пороге"))
    }

    @Test fun warningDoesNotHideOrMergeLeafPredictions() {
        val labels = listOf("Томат: здоровый", "Томат: фитофтороз")
        val speech = spokenAnalysis(labels, dark)
        assertEquals(AnalysisAssessment.PHOTO_WARNING, assessAnalysis(labels.size, dark))
        assertTrue(speech.startsWith(dark.advice!!))
        labels.forEach { assertTrue(speech.contains(it)) }
        assertTrue(speech.contains("Предварительный результат"))
    }

    @Test fun absentQualityRemainsPreliminaryAndSpeechKeepsItsLimit() {
        assertEquals(AnalysisAssessment.PRELIMINARY, assessAnalysis(1, null))
        val speech = spokenAnalysis(List(7) { "метка-${it + 1}" }, null)
        assertTrue(speech.contains("метка-5"))
        assertFalse(speech.contains("метка-6"))
        assertTrue(speech.endsWith("Результат нужно проверить."))
    }

    @Test fun noPhotoWarningDoesNotCertifyTheDiagnosis() {
        val pixels = IntArray(64) { index ->
            if ((index / 8 + index % 8) % 2 == 0) 0xFF505050.toInt() else 0xFFB0B0B0.toInt()
        }
        val quality = analysePhoto(pixels, 8, 8)
        assertEquals(PhotoVerdict.NO_WARNING, quality.verdict)
        assertEquals(AnalysisAssessment.PRELIMINARY, assessAnalysis(1, quality))
        assertTrue(spokenAnalysis(listOf("Томат: здоровый"), quality).contains("Результат нужно проверить"))
    }

    @Test fun qualityAndAssessmentSurviveJsonRoundTripWithoutChangingDetections() {
        val report = JSONObject().put("detections", JSONArray().put(JSONObject().put("class_id", 3)))
        addAnalysisAssessment(report, 1, dark)
        val restored = JSONObject(report.toString())
        assertEquals(3, restored.getJSONArray("detections").getJSONObject(0).getInt("class_id"))
        assertEquals("photo_warning", restored.getJSONObject("assessment").getString("status"))
        val quality = restored.getJSONObject("photo_quality")
        assertEquals("dark", quality.getString("verdict"))
        assertEquals(4, quality.getInt("sample_width"))
        assertEquals(4, quality.getInt("sample_height"))
        assertEquals(0.0, quality.getDouble("mean_luma"), 0.0)
        assertEquals(0.0, quality.getDouble("laplacian_variance"), 0.0)
        assertFalse(quality.getBoolean("calibrated"))
        assertFalse(quality.getBoolean("blocking"))
    }

    @Test fun unmeasuredQualityAndSharpnessAreExplicitNulls() {
        val report = JSONObject()
        addAnalysisAssessment(report, 0, null)
        assertTrue(report.has("photo_quality"))
        assertTrue(report.isNull("photo_quality"))
        assertEquals("no_detections", report.getJSONObject("assessment").getString("status"))
        val tiny = analysePhoto(intArrayOf(0), 1, 1)
        addAnalysisAssessment(report, 1, tiny)
        val restored = JSONObject(report.toString())
        assertTrue(restored.getJSONObject("photo_quality").isNull("laplacian_variance"))
        assertEquals("not_assessed", restored.getJSONObject("photo_quality").getString("verdict"))
    }
}
