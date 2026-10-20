package com.agronomy.phytomonitor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.draft.DraftFrame
import com.agronomy.phytomonitor.draft.InspectionDraft
import com.agronomy.phytomonitor.draft.InspectionDraftStore
import com.agronomy.phytomonitor.journal.FrameRole
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.ml.ModelCatalog
import com.agronomy.phytomonitor.voice.VoiceAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Synthetic stored reports only: no inference, agronomic labels, live speech or production data. */
@RunWith(AndroidJUnit4::class)
class SeriesDeviceTest {
    @Test
    fun twoFramesSurviveSelectionRecreationSaveAndPortableArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.agronomy.phytomonitor.verification") {
            "This test requires the isolated verification package"
        }
        val store = InspectionDraftStore(File(context.filesDir, "inspection-draft.json"))
        check(store.read() == null) { "An existing draft must be completed before this isolated test" }
        val fixtureId = UUID.randomUUID().toString()
        val catalog = context.assets.open("models/manifest.json").bufferedReader().use { it.readText() }
        val model = ModelCatalog.parse(JSONObject(catalog)).first { it.family == "yolo" }
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val ownedFiles = mutableListOf<File>()
        val journal = InspectionJournal(context)
        var savedId: String? = null
        try {
            val frames = (0..1).map { index ->
                val photo = File(root, "photos/series-$fixtureId-$index.png")
                photo.parentFile!!.mkdirs()
                ownedFiles += photo
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
                    bitmap.eraseColor(if (index == 0) android.graphics.Color.GRAY else android.graphics.Color.BLUE)
                    photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val report = File(root, "results/series-$fixtureId-$index.json")
                report.parentFile!!.mkdirs()
                ownedFiles += report
                val raw = JSONObject().put("photo", photo.path).put("photo_sha256", InspectionJournal.sha256(photo))
                    .put("model", model.id).put("model_sha256", model.sha256)
                    .put("decoded_width", 32).put("decoded_height", 32)
                    .put("inference_ms", 0).put("pipeline_ms", 0).put("detections", JSONArray())
                    .put("test_fixture", "Synthetic report $index; no inference performed").toString()
                report.writeText(raw, Charsets.UTF_8)
                DraftFrame("$fixtureId-$index", photo.path, reportName = report.name,
                    reportJson = raw, catalogJson = catalog)
            }
            val draft = InspectionDraft(id = fixtureId, photo = frames.first().photo, frames = frames,
                activeFrameId = frames.first().id, note = "TEST: synthetic two-frame series", model = model.id)
                .selectFrame(frames.first().id)
            store.write(draft)
            ActivityScenario.launch(FieldDemoActivity::class.java).use { scenario ->
                await(scenario) { !it.fieldModel.busy && it.fieldModel.hasDraft }
                dispatch(scenario, VoiceAction.RESUME_DRAFT)
                await(scenario) { !it.fieldModel.busy && !it.fieldModel.hasDraft }
                dispatch(scenario, VoiceAction.SELECT_FRAME, "2")
                await(scenario) { !it.fieldModel.busy && it.fieldModel.selectedFrame == 2 }
                dispatch(scenario, VoiceAction.FRAME_ROLE, "back")
                await(scenario) { !it.fieldModel.draftSaving && it.fieldModel.frameRole == FrameRole.BACK }
                val persisted = requireNotNull(store.read())
                assertEquals(frames.map { it.reportJson }, persisted.allFrames().map { it.reportJson })
                assertEquals(frames.last().id, persisted.activeFrameId)
                scenario.recreate()
                await(scenario) { !it.fieldModel.busy && it.fieldModel.selectedFrame == 2 }
                scenario.onActivity { assertEquals(frames.last().reportName, it.fieldModel.reportName) }
                dispatch(scenario, VoiceAction.SELECT_FRAME, "1")
                await(scenario) { !it.fieldModel.busy && it.fieldModel.selectedFrame == 1 }
                dispatch(scenario, VoiceAction.SAVE_INSPECTION)
                await(scenario) { !it.fieldModel.busy && it.fieldModel.inspectionSaved }
                savedId = requireNotNull(JournalDatabase.get(context).journal()
                    .inspectionByReport(requireNotNull(frames.first().reportName))).id
                dispatch(scenario, VoiceAction.SAVE_INSPECTION)
                await(scenario) { !it.fieldModel.busy }
                assertNull(store.read())
            }
            val details = requireNotNull(journal.details(requireNotNull(savedId)))
            assertEquals(2, details.photos.size)
            assertEquals(frames.map { it.reportJson }, details.photos.map { it.photo.predictionJson })
            assertEquals(FrameRole.BACK.value, details.photos.last().photo.frameRole)
            assertTrue(details.photos.all { it.detections.isEmpty() })

            // A separate context and Room database prove portability without importing into the app journal.
            val portableRoot = File(context.cacheDir, "series-portable-$fixtureId")
            val sourceContext = PortableContext(context, File(portableRoot, "source"))
            val restoredContext = PortableContext(context, File(portableRoot, "restored"))
            val sourceDb = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).build()
            try {
                val source = InspectionJournal(sourceContext, sourceDb)
                val record = requireNotNull(journal.record(requireNotNull(savedId)))
                details.photos.forEachIndexed { index, frame ->
                    val copy = File(sourceContext.filesDir, "photos/$index.png")
                    copy.parentFile!!.mkdirs()
                    File(frame.photo.localPath).copyTo(copy)
                    record.getJSONArray("frames").getJSONObject(index).put("photo_path", copy.path)
                }
                val document = com.agronomy.phytomonitor.journal.JournalRecords.document(JSONArray().put(record), false, System.currentTimeMillis())
                assertEquals(1, source.import(ByteArrayInputStream(document.toString().toByteArray(Charsets.UTF_8))).added)
                val archive = ByteArrayOutputStream().also { source.exportArchive(it) }.toByteArray()
                val restoredDb = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).build()
                try {
                    val restored = InspectionJournal(restoredContext, restoredDb)
                    assertEquals(1, restored.import(ByteArrayInputStream(archive)).added)
                    assertEquals(1, restored.import(ByteArrayInputStream(archive)).unchanged)
                    val recovered = requireNotNull(restored.details(requireNotNull(savedId)))
                    assertEquals(details.photos.map { it.photo.predictionJson }, recovered.photos.map { it.photo.predictionJson })
                    assertEquals(details.photos.map { it.photo.sha256 }, recovered.photos.map { it.photo.sha256 })
                    assertTrue(recovered.photos.all { File(it.photo.localPath).isFile })
                } finally {
                    restoredDb.close()
                }
            } finally {
                sourceDb.close()
            }
            File(root, "series_device_report.json").writeText(JSONObject()
                .put("status", "passed").put("fixture_id", fixtureId)
                .put("scope", "Isolated Android; synthetic reports with no inference; injected dispatcher requests, not live speech")
                .put("frames", 2).put("selection_and_role", true).put("activity_recreation", true)
                .put("repeat_save", true).put("archive_roundtrip_and_repeat", true).toString(2), Charsets.UTF_8)
        } finally {
            savedId?.let { journal.deleteForTest(it) }
            if (store.read()?.id == fixtureId) store.clear(fixtureId)
            ownedFiles.forEach { it.delete() }
        }
    }

    private fun dispatch(scenario: ActivityScenario<FieldDemoActivity>, action: VoiceAction, value: String = "") {
        scenario.onActivity { it.actions.dispatch(ActionRequest(action, value, ActionSource.BUTTON)) }
    }

    private fun await(scenario: ActivityScenario<FieldDemoActivity>, condition: (FieldDemoActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var diagnostic = "Activity not sampled"
        while (true) {
            var ready = false
            scenario.onActivity {
                diagnostic = "${it.currentState()}; ${it.fieldModel.message}; ${it.fieldModel.draftError}"
                ready = condition(it) && !it.currentState().busy
            }
            if (ready) return
            check(SystemClock.elapsedRealtime() < deadline) { "Series fixture timed out: $diagnostic" }
            SystemClock.sleep(25)
        }
    }

    private class PortableContext(context: Context, private val root: File) : ContextWrapper(context) {
        override fun getExternalFilesDir(type: String?): File = File(root, "files").apply { mkdirs() }
        override fun getFilesDir(): File = getExternalFilesDir(null)
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
