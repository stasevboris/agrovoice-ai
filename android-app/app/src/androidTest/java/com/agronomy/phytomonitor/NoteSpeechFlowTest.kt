package com.agronomy.phytomonitor

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NoteSpeechFlowTest {
    @get:Rule val compose=createAndroidComposeRule<FieldDemoActivity>()
    private var testPhoto: File? = null
    private var testReport: File? = null
    private var previousPhoto: String? = null
    private var previousPlot = ""
    private var prepared = false
    private var originalRecords = emptyMap<String, String>()

    @Before
    fun prepareOwnInspection() {
        val activity = compose.activity
        val model = activity.fieldModel
        val preferences = activity.getSharedPreferences("field_demo", 0)
        previousPhoto = preferences.getString("last_photo", null)
        previousPlot = model.plotName
        originalRecords = InspectionJournal(activity).let { journal ->
            journal.list().associate { it.id to journal.record(it.id).toString() }
        }
        prepared = true
        compose.waitUntil(60000) { !model.busy }
        val photo = File.createTempFile("note_speech_", ".png", activity.cacheDir)
        testPhoto = photo
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        compose.runOnUiThread {
            activity.setContinuous(false)
            model.editPlot("")
            model.openFile(photo)
        }
        idle()
        testReport = File(activity.getExternalFilesDir(null), "results/${model.reportName!!}")
    }

    @After
    fun removeOnlyOwnInspection() {
        if (!prepared) return
        val activity = compose.activity
        val model = activity.fieldModel
        compose.runOnUiThread {
            activity.setContinuous(false)
            activity.voiceController.stop()
            activity.resultSpeaker.stop()
            model.noteOpen = false
            model.dictating = false
            model.noteReviewVoice = false
        }
        // Let an in-flight save finish before inspecting or removing our record.
        compose.waitUntil(60000) { !model.busy }
        val journal = InspectionJournal(activity)
        val reportName = testReport?.name
        journal.list().filter {
            it.id !in originalRecords && journal.record(it.id)?.optString("report_name") == reportName
        }
            .forEach { journal.deleteForTest(it.id) }
        compose.runOnUiThread { model.editPlot(previousPlot) }
        activity.getSharedPreferences("field_demo", 0).edit()
            .putString("last_photo", previousPhoto).commit()
        testReport?.let { assertTrue("Test report was not removed", !it.exists() || it.delete()) }
        testPhoto?.let { assertTrue("Test photo was not removed", !it.exists() || it.delete()) }
        assertEquals(originalRecords, journal.list().associate { it.id to journal.record(it.id).toString() })
    }
    private fun idle(){compose.waitUntil(60000){
        val model=compose.activity.fieldModel
        if(model.result==null && model.bitmap!=null && !model.busy)compose.activity.runOnUiThread {model.analyze()}
        !model.busy && model.result!=null
    }}
    private fun folder()=File(compose.activity.getExternalFilesDir(null),
        InstrumentationRegistry.getArguments().getString("voiceFolder") ?: "voice_features_v1_phone")
    private fun listening(){compose.waitUntil(30000){compose.activity.voiceListening && !compose.activity.voiceController.status.contains("Подготавливаю")}}
    private fun pcm(bytes: ByteArray): ByteArray {
        var offset=12
        while(offset+8<=bytes.size){
            val size=ByteBuffer.wrap(bytes,offset+4,4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size>=0 && offset+8+size<=bytes.size)
            if(String(bytes,offset,4,Charsets.US_ASCII)=="data")return bytes.copyOfRange(offset+8,offset+8+size)
            offset+=8+size+(size%2)
        }
        error("No WAV data")
    }
    @Test fun noteUsesFreeSpeechThenReviewAndVoiceConfirmation() {
        idle();val activity=compose.activity;val vm=activity.fieldModel
        val journal=InspectionJournal(activity)
        val before=journal.list().map {it.id}.toSet()
        val cases=JSONArray(File(folder(),"cases.json").readText().removePrefix("\uFEFF"))
        for (voice in listOf("irina", "pavel")) {
            for (id in listOf("note_camera", "note_phrase", "note_end", "camera_confirm")) {
                assertTrue("Missing sample $voice/$id in ${folder().name}",
                    (0 until cases.length()).map { cases.getJSONObject(it) }.any {
                        it.getString("id") == id && it.getString("file").startsWith(voice) &&
                            File(folder(), it.getString("file")).isFile
                    })
            }
        }
        val decoded=JSONArray()
        try {
            compose.runOnUiThread {activity.setContinuous(false);vm.editNote("TEST: PCM-flow")}
            Model(VoiceModelFiles.prepare(activity).absolutePath).use {nativeModel->
                fun feed(id: String,voice: String,screen: String){
                    val sample=(0 until cases.length()).map {cases.getJSONObject(it)}.first {it.getString("id")==id && it.getString("file").startsWith(voice)}
                    val audio=pcm(File(folder(),sample.getString("file")).readBytes())
                    val results=mutableListOf<String>()
                    val grammar=if(screen=="note")null else JSONArray(VoiceCommands.noteReviewGrammar).toString()
                    (if(grammar==null)Recognizer(nativeModel,16000f) else Recognizer(nativeModel,16000f,grammar)).use {engine->
                        engine.setWords(true)
                        var position=0
                        while(position<audio.size){val block=audio.copyOfRange(position,minOf(position+8000,audio.size));if(engine.acceptWaveForm(block,block.size))results+=engine.result;position+=block.size}
                        results+=engine.finalResult
                    }
                    val nonempty=results.filter {JSONObject(it).optString("text").isNotBlank()}
                    assertTrue("No decoded phrase: $id/$voice",nonempty.isNotEmpty())
                    nonempty.forEach {raw->
                        decoded.put(JSONObject().put("file",sample.getString("file")).put("screen",screen).put("hypothesis",JSONObject(raw)))
                        compose.runOnUiThread {activity.voiceController.acceptFinalHypothesis(raw)}
                    }
                }
                for(voice in listOf("irina","pavel")){
                    compose.runOnUiThread { vm.editNote("TEST: PCM-flow $voice") }
                    compose.runOnUiThread {activity.handleVoiceCommand(VoiceCommands.parse("добавь заметку")!!)}
                    listening();assertTrue(vm.dictating)
                    feed("note_camera",voice,"note")
                    assertFalse(activity.photoFlow.cameraOpen);assertTrue(vm.noteDraft.contains("фото"))
                    feed("note_phrase",voice,"note");assertTrue(vm.noteDraft.length>20)
                    feed("note_end",voice,"note")
                    compose.waitUntil(30000){!vm.dictating && vm.noteReviewVoice && activity.voiceListening}
                    feed("camera_confirm",voice,"note_review")
                    compose.waitUntil(30000){!vm.noteOpen};idle()
                    assertTrue(InspectionJournal(activity).list().any {
                        it.id !in before && it.note.startsWith("TEST: PCM-flow $voice") && it.note.contains("фото")
                    })
                }
                compose.runOnUiThread {activity.handleVoiceCommand(VoiceCommands.parse("добавь заметку")!!)};listening()
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(vm.dictating);assertFalse(activity.voiceListening)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                assertFalse(vm.dictating)
                compose.runOnUiThread {activity.handleVoiceCommand(VoiceCommands.parse("отмена")!!)}
            }
            File(folder(),"note_speech_flow.json").writeText(JSONObject().put("status","passed").put("real_microphone_sessions",true)
                .put("decoded_pcm_to_production_callback",decoded).put("camera_phrase_remained_text",true).put("background_stopped",true)
                .put("scope","Actual microphone sessions and actual Android Vosk PCM decoding routed through active production callbacks. WAVs were not played into the microphone; no acoustic/live-human accuracy claim.").toString(2))
        }finally {
            File(folder(),"note_speech_debug.json").writeText(JSONObject().put("decoded",decoded).put("dictating",vm.dictating).put("review",vm.noteReviewVoice).put("note_open",vm.noteOpen).put("voice_active",activity.voiceListening).put("voice_status",activity.voiceController.status).toString(2))
            compose.runOnUiThread {activity.setContinuous(false);activity.voiceController.stop();vm.noteOpen=false;vm.dictating=false;vm.noteReviewVoice=false;vm.editNote("")}
        }
    }
    @Test fun offlineSpeechReportsCompletionOrUnavailableAndStopsInBackground() {
        idle();val activity=compose.activity;val speaker=activity.resultSpeaker
        compose.waitUntil(30000){speaker.initialized}
        compose.runOnUiThread {activity.setContinuous(false);activity.voiceController.start()};listening()
        val available=speaker.available
        if(available){
            compose.runOnUiThread {activity.readResult(volume=0f)}
            assertFalse(activity.voiceListening)
            compose.waitUntil(60000){!speaker.active && speaker.completion!="pending"}
            val callback=speaker.completion
            assertTrue(callback=="done" || callback.startsWith("error:"))
            compose.runOnUiThread {speaker.speak("Проверка остановки озвучивания. ".repeat(30),volume=0f)}
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(speaker.active);assertFalse(activity.voiceListening)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            File(folder(),"tts_flow.json").writeText(JSONObject().put("status","passed").put("offline_voice_available",true).put("voice",speaker.voiceName)
                .put("speech_callback",callback).put("microphone_paused",true).put("background_stopped",true).put("volume",0).put("scope","Actual Android TTS request/callback at volume0; no audible speaker or field acoustic test.").toString(2))
        }else{
            compose.runOnUiThread {activity.readResult(volume=0f)}
            assertFalse(speaker.active);assertTrue(activity.fieldModel.message!!.contains("недоступен"))
            File(folder(),"tts_flow.json").writeText(JSONObject().put("status","passed").put("offline_voice_available",false).put("visible_fallback",speaker.status)
                .put("speech_callback",JSONObject.NULL).put("scope","Missing installed Russian offline voice handled explicitly. No voice/data download or speech success claimed.").toString(2))
        }
    }
}
