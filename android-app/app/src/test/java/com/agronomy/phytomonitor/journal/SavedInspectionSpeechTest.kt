package com.agronomy.phytomonitor.journal

import org.junit.Assert.*
import org.junit.Test

class SavedInspectionSpeechTest {
    @Test
    fun readsPersistedLabelsAndNoteEvenWhenPhotoIsMissing() {
        val record = JournalRecords.toRecord(JournalRecords.parse(
            JournalFixtures.legacyRecord("saved", "/missing.jpg", note = "Повторить осмотр")), null, "/missing.jpg")
        val details = InspectionWithDetails(record.inspection, null,
            listOf(PhotoWithDetections(record.photo, record.detections)))
        val text = SavedInspectionSpeech.text(details)
        assertTrue(text.contains("Томат: фитофтороз"))
        assertTrue(text.contains("Повторить осмотр"))
        assertTrue(text.contains("yolo26n"))
        assertTrue(text.contains("не подтверждает диагноз"))
        val empty = SavedInspectionSpeech.text(details.copy(photos = emptyList()))
        assertTrue(empty.contains("листьев выше порога не найдено"))
        assertFalse(empty.contains("Томат: фитофтороз"))
    }
}
