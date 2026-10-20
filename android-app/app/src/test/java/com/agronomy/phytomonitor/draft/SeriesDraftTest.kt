package com.agronomy.phytomonitor.draft

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.agronomy.phytomonitor.journal.FrameRole
import com.agronomy.phytomonitor.seriesClarification
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesDraftTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun selectionAndAppendKeepPrimaryIdentityAndAllMetadataOnDisk() {
        val primary = DraftFrame("first", "/front.jpg", FrameRole.FRONT, "first.json", "original report", "original catalog")
        val initial = InspectionDraft(id = "inspection", photo = primary.photo, frames = listOf(primary),
            activeFrameId = primary.id, note = "Не потерять", plot = "Теплица", row = "14", plant = "Растение 7")
            .selectFrame(primary.id)
        val added = initial.appendFrame("/back.jpg")
        val last = added.allFrames().last().copy(role = FrameRole.BACK, reportName = "second.json",
            reportJson = "second report", catalogJson = "second catalog")
        val completed = added.updateFrame(last)
        val store = InspectionDraftStore(File(temporary.root, "draft.json"))
        store.write(completed)
        val restored = InspectionDraftStore(File(temporary.root, "draft.json")).read()!!
        assertEquals("first.json", restored.primaryReportName)
        assertEquals("second.json", restored.reportName)
        assertEquals(primary, restored.allFrames().first())
        assertEquals("Не потерять", restored.note)
        assertEquals("Теплица", restored.plot)
        assertEquals("14", restored.row)
        assertEquals("Растение 7", restored.plant)
        assertEquals("first.json", restored.selectFrame("first").reportName)
        assertEquals(listOf(primary, last), restored.selectFrame("first").allFrames())
    }

    @Test
    fun rejectsDuplicateAndUnknownFrameIdentityAndEleventhFrame() {
        val single = InspectionDraft(photo = "/photo.jpg")
        val json = single.toJson().put("schema", 2).put("active_frame_id", "front")
        val first = frame("front", "/a.jpg", "front", "a.json", "a")
        json.put("frames", JSONArray().put(first).put(first))
        assertThrows(IllegalArgumentException::class.java) { InspectionDraft.fromJson(json) }
        json.put("frames", JSONArray().put(first)).put("active_frame_id", "missing")
        assertThrows(IllegalArgumentException::class.java) { InspectionDraft.fromJson(json) }
        var full = single
        repeat(9) { full = full.appendFrame("/next$it.jpg") }
        assertThrows(IllegalArgumentException::class.java) { full.appendFrame("/eleventh.jpg") }
        assertEquals(10, full.allFrames().size)
    }

    @Test
    fun legacyDraftRestoresOneStableFrameAndNoInventedAnalysis() {
        val original = InspectionDraft(id = "legacy", photo = "/old.jpg", reportName = "old.json")
        val restored = InspectionDraft.fromJson(original.toJson())
        assertEquals(original, restored)
        assertEquals("legacy", restored.allFrames().single().id)
        assertEquals(FrameRole.UNSPECIFIED, restored.allFrames().single().role)
        assertNull(restored.result())
    }

    @Test
    fun selectingRestoredFrameUsesItsOriginalModelAndConfidence() {
        fun analysed(id: String, hash: String, label: String, confidence: Double): DraftFrame {
            val report = com.agronomy.phytomonitor.journal.JournalFixtures.prediction("/$id.jpg")
                .put("model_sha256", hash).put("inference_ms", 12.0).put("pipeline_ms", 20.0)
            report.getJSONArray("detections").getJSONObject(0).put("confidence", confidence)
            val catalog = JSONObject().put("schema", 3).put("models", JSONArray().put(JSONObject()
                .put("id", "yolo26n").put("name", id).put("asset", "$id.onnx").put("sha256", hash)
                .put("classes", JSONArray(listOf("class_$id"))).put("labels_ru", JSONArray(listOf(label)))))
            return DraftFrame(id, "/$id.jpg", reportName = "$id.json", reportJson = report.toString(), catalogJson = catalog.toString())
        }
        val first = analysed("first", "original-first", "Первая исходная подпись", 0.7)
        val second = analysed("second", "original-second", "Вторая исходная подпись", 0.4)
        val draft = InspectionDraft(photo = first.photo, frames = listOf(first, second), activeFrameId = "first")
            .selectFrame("second")
        val restored = InspectionDraft.fromJson(draft.toJson())
        assertEquals("original-second", restored.result()!!.model.sha256)
        assertEquals("Вторая исходная подпись", restored.result()!!.model.labels.single())
        assertEquals(0.4f, restored.result()!!.boxes.single().confidence, 0f)
        assertEquals("original-first", restored.selectFrame("first").result()!!.model.sha256)
        assertEquals(0.7f, restored.selectFrame("first").result()!!.boxes.single().confidence, 0f)
    }

    @Test
    fun disagreementPromptsClarificationWithoutAveragingConfidence() {
        fun report(code: String, confidence: Double) = JSONObject().put("detections", JSONArray()
            .put(JSONObject().put("class_name", code).put("confidence", confidence))).toString()
        val front = report("tomato__healthy", 0.9)
        val back = report("tomato__late_blight", 0.6)
        assertNotNull(seriesClarification(listOf(front, back)))
        assertNull(seriesClarification(listOf(front, report("tomato__healthy", 0.2))))
        assertNull(seriesClarification(listOf(front, null)))
        assertEquals(0.9, JSONObject(front).getJSONArray("detections").getJSONObject(0).getDouble("confidence"), 0.0)
    }

    @Test
    fun seriesSurvivesRestartWithSelectedFrameAndIndependentReports() {
        val json = InspectionDraft(id = "inspection", photo = "/back.jpg", note = "Одно растение")
            .toJson().put("schema", 2).put("active_frame_id", "back")
            .put("frames", JSONArray()
                .put(frame("front", "/front.jpg", "front", "first.json", "first report"))
                .put(frame("back", "/back.jpg", "back", "second.json", "second report")))
        val restored = InspectionDraft.fromJson(JSONObject(json.toString())).toJson()
        assertEquals("back", restored.getString("active_frame_id"))
        assertEquals("Одно растение", restored.getString("note"))
        val frames = restored.getJSONArray("frames")
        assertEquals(2, frames.length())
        assertEquals("front", frames.getJSONObject(0).getString("id"))
        assertEquals("first report", frames.getJSONObject(0).getString("report"))
        assertEquals("second report", frames.getJSONObject(1).getString("report"))
        assertEquals("back", frames.getJSONObject(1).getString("role"))
    }

    private fun frame(id: String, photo: String, role: String, name: String, report: String) =
        JSONObject().put("id", id).put("photo", photo).put("role", role)
            .put("report_name", name).put("report", report).put("catalog", "original catalog")
}
