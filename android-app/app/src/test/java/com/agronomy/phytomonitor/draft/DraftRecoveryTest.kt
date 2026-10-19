package com.agronomy.phytomonitor.draft

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.journal.JournalFixtures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun lostSaveResponseFindsOneCommittedRecordAndNeverReplaysOldNote() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val journal = InspectionJournal(context, database, clock = { 1000L })
            val photo = temporary.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val report = temporary.newFile("report.json").apply {
                writeText(JournalFixtures.prediction(photo.path, InspectionJournal.sha256(photo)).toString())
            }
            val store = InspectionDraftStore(File(temporary.root, "draft.json"))
            val pending = InspectionDraft(photo = photo.path, reportName = report.name,
                note = "до потери ответа", saveRequested = true)
            store.write(pending)
            val first = journal.save(report, pending.note, "unrated")
            val version = journal.details(first.id)!!.inspection.updatedAt
            journal.editNote(first.id, version, "Более новая исправленная заметка")
            val reopened = InspectionDraftStore(File(temporary.root, "draft.json")).read()!!
            assertTrue(reopened.saveCompleted(database.journal().inspectionByReport(report.name)!!.updatedAt))
            store.clear(reopened.id)
            assertEquals(1, journal.list().size)
            assertEquals("Более новая исправленная заметка", journal.details(first.id)!!.inspection.note)
            assertNull(store.read())
        } finally {
            database.close()
        }
    }

    @Test
    fun restoredResultUsesOriginalClassesHashAndParametersAfterModelReplacement() {
        val report = JournalFixtures.prediction("/original.jpg").put("inference_ms", 12.0)
            .put("pipeline_ms", 20.0).put("crop_filter", "tomato").put("confidence_threshold", 0.25)
        val catalog = JSONObject().put("schema", 3).put("models", JSONArray().put(JSONObject()
            .put("id", "yolo26n").put("name", "Исходная модель").put("asset", "old.onnx")
            .put("sha256", "abc123").put("classes", JSONArray(listOf("tomato__late_blight")))
            .put("labels_ru", JSONArray(listOf("Исходная подпись")))))
        val draft = InspectionDraft(photo = "/original.jpg", reportName = "result.json",
            reportJson = report.toString(), catalogJson = catalog.toString(), model = "replacement", threshold = 0.8f)
        val store = InspectionDraftStore(File(temporary.root, "draft.json"))
        store.write(draft)
        catalog.getJSONArray("models").getJSONObject(0).put("sha256", "new-hash")
        val restored = store.read()!!
        val result = restored.result()!!
        assertEquals("abc123", result.model.sha256)
        assertEquals("Исходная подпись", result.model.labels[result.boxes.single().classId])
        assertEquals(0.25, JSONObject(restored.reportJson!!).getDouble("confidence_threshold"), 0.0)
        assertEquals("tomato", JSONObject(restored.reportJson).getString("crop_filter"))
        assertEquals(report.toString(), restored.reportJson)
    }

    @Test
    fun rejectedReplacementKeepsThePrecedingDraft() {
        val store = InspectionDraftStore(File(temporary.root, "draft.json"))
        val original = InspectionDraft(photo = "/original.jpg", note = "Не потерять")
        store.write(original)
        assertThrows(IllegalArgumentException::class.java) { store.write(original.copy(note = "x".repeat(3 * 1024 * 1024))) }
        assertEquals(original, store.read())
    }
}
