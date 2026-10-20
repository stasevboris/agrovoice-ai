package com.agronomy.phytomonitor.journal

import com.agronomy.phytomonitor.SavedInspectionView
import org.junit.Assert.*
import org.junit.Test

class SeriesViewTest {
    @Test
    fun selectedFrameSuppliesOwnLabelsConfidenceProvenanceAndSpeech() {
        val first = JournalRecords.toRecord(JournalRecords.parse(JournalFixtures.legacyRecord("saved", "/front.jpg")), null, null, "/front.jpg")
        val backReport = JournalFixtures.prediction("/back.jpg", labels = listOf("Огурец: лист"))
            .put("model", "back-model").toString()
        val second = first.photo.copy(id = "second", localPath = "/back.jpg", frameOrder = 1,
            frameRole = "back", predictionJson = backReport)
        val backDetection = first.detections.single().copy(photoId = "second", labelRu = "Огурец: лист", confidence = 0.42)
        val details = InspectionWithDetails(first.inspection, null, listOf(
            PhotoWithDetections(first.photo, first.detections), PhotoWithDetections(second, listOf(backDetection))))
        val view = SavedInspectionView.of(details, null, 2)
        assertEquals(listOf("Огурец: лист"), view.labels)
        assertEquals(0.42f, view.boxes.single().confidence, 0f)
        assertEquals(backReport, view.predictionJson)
        val speech = SavedInspectionSpeech.text(details, 2)
        assertTrue(speech.contains("Огурец: лист"))
        assertTrue(speech.contains("back-model"))
        assertFalse(speech.contains("Томат: фитофтороз"))
        val unknown = SavedInspectionView.of(details.copy(photos = listOf(details.photos[0],
            PhotoWithDetections(second.copy(predictionJson = null), emptyList()))), null, 2)
        assertNull(unknown.predictionJson)
        assertTrue(unknown.labels.isEmpty())
    }
}
