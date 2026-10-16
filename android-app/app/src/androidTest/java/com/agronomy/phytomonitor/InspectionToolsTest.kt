package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.core.content.FileProvider

class InspectionToolsTest {
    @get:Rule val compose=createAndroidComposeRule<FieldDemoActivity>()
    private fun idle(){compose.waitUntil(60000){!compose.activity.fieldModel.busy && compose.activity.fieldModel.result!=null}}
    private fun say(text: String){compose.runOnUiThread {compose.activity.handleVoiceCommand(VoiceCommands.parseForScreen(text,"main")!!)}}
    @Test fun explicitJournalUsesExactPredictionAndUpdatesWithoutDuplicate() {
        idle();val model=compose.activity.fieldModel;val report=model.reportName!!
        val directory=File(compose.activity.getExternalFilesDir(null),"inspections");val before=directory.listFiles()?.map {it.name}?.toSet() ?: emptySet()
        val previousPlot=model.plotName
        try {
            compose.runOnUiThread {model.editNote("TEST: картофель, участок один");model.editPlot("TEST: грядка 7")};say("сохрани осмотр");idle()
            val first=model.journalEntries.first {it.note.startsWith("TEST:")};assertEquals(model.result!!.model.id,first.model)
            val file=File(directory,"${first.id}.json");val json=JSONObject(file.readText())
            assertEquals(report,json.getString("report_name"));assertEquals(model.result!!.model.sha256,json.getJSONObject("prediction").getString("model_sha256"))
            say("результат неверный");idle()
            assertEquals("incorrect",JSONObject(file.readText()).getString("review"))
            assertEquals(1,model.journalEntries.count {it.id==first.id})
            val reloaded=InspectionJournal(compose.activity).list().first {it.id==first.id}
            assertEquals("incorrect",reloaded.review);assertEquals(first.note,reloaded.note)
            assertEquals("TEST: грядка 7",reloaded.plot)
            compose.runOnUiThread {model.journalQuery="ГРЯДКА 7";model.journalFilter="incorrect"}
            assertEquals(listOf(first.id),model.visibleInspections.map {it.id})
            compose.runOnUiThread {model.journalFilter="correct"};assertTrue(model.visibleInspections.isEmpty())
            val exported=File(compose.activity.getExternalFilesDir(null),"results/test-export-${System.nanoTime()}.json")
            try {
                exported.writeText("")
                val uri=FileProvider.getUriForFile(compose.activity,"com.agronomy.phytomonitor.files",exported)
                compose.runOnUiThread {model.exportJournal(uri)};idle()
                val rows=JSONObject(exported.readText()).getJSONArray("records")
                val saved=(0 until rows.length()).map {rows.getJSONObject(it)}.first {it.getString("id")==first.id}
                assertEquals(model.result!!.model.sha256,saved.getJSONObject("prediction").getString("model_sha256"))
                assertEquals("incorrect",saved.getString("review"));assertEquals(reloaded.plot,saved.getString("plot"))
            }finally {exported.delete()}
            compose.runOnUiThread {model.journalQuery="";model.journalFilter="all"}
            compose.runOnUiThread {model.noteOpen=true;model.dictating=false;model.noteReviewVoice=true;model.editNote("TEST: подтверждение заметки")}
            compose.runOnUiThread {compose.activity.handleVoiceCommand(VoiceCommands.parseForScreen("подтвердить","note_review")!!)};idle()
            assertFalse(model.noteOpen);assertEquals("TEST: подтверждение заметки",InspectionJournal(compose.activity).list().first {it.id==first.id}.note)
            say("открой журнал");assertTrue(model.journalOpen);say("назад");assertFalse(model.journalOpen)
        } finally {
            compose.runOnUiThread {model.editPlot(previousPlot);model.journalQuery="";model.journalFilter="all"}
            directory.listFiles()?.filter {it.name !in before && it.extension=="json"}?.forEach {file->
                if(JSONObject(file.readText()).getString("note").startsWith("TEST:"))file.delete()
            }
        }
        val evidence=File(compose.activity.getExternalFilesDir(null),InstrumentationRegistry.getArguments().getString("voiceFolder") ?: "features_v1_phone").apply {mkdirs()}
        File(evidence,"inspection_tools.json").writeText(JSONObject().put("status","passed").put("report_binding",true).put("no_duplicate",true).put("persisted_review",true).put("plot_and_search",true).put("content_uri_export",true).put("own_test_entries_removed",true).toString(2))
    }
    @Test fun comparisonKeepsCurrentPhotoAndSettingsAndReturnsAllRealModes() {
        idle();val model=compose.activity.fieldModel;val photo=model.photo;val chosen=model.selectedModel;val threshold=model.threshold
        say("скрой рамки");assertFalse(model.showBoxes);say("покажи рамки");assertTrue(model.showBoxes)
        say("сравни модели");compose.waitUntil(120000){!model.busy && model.comparison.size==model.detector.models.size}
        assertEquals(photo,model.photo);assertEquals(chosen,model.selectedModel);assertEquals(threshold,model.threshold)
        assertEquals(model.detector.models.map {it.id},model.comparison.map {it.second.model.id})
        assertTrue(model.comparison.all {it.second.pipelineMs>0})
        val evidence=File(compose.activity.getExternalFilesDir(null),InstrumentationRegistry.getArguments().getString("voiceFolder") ?: "features_v1_phone").apply {mkdirs()}
        File(evidence,"comparison_tools.json").writeText(JSONObject().put("status","passed").put("modes",model.comparison.size).put("photo_and_settings_preserved",true).toString(2))
    }
}
