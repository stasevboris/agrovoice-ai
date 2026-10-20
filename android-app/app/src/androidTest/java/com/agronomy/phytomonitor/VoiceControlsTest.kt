package com.agronomy.phytomonitor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.photos.LocalPhotoGallery
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.RussianPhotoNumber
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import android.net.Uri
import com.agronomy.phytomonitor.settings.AppSettings
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.settings.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Real foreground microphone, camera and MediaStore; ASR audio decoding is checked separately. */
class VoiceControlsTest {
    @get:Rule val compose=createAndroidComposeRule<FieldDemoActivity>()
    private lateinit var originalSettings: AppSettings
    private var previousFiles = emptySet<String>()
    private var previousRecords = emptyMap<String, String>()
    private val ownFiles = linkedSetOf<File>()

    @Before
    fun preserveState() {
        val activity = compose.activity
        val store = SettingsStore.get(activity)
        originalSettings = runBlocking { store.state.filterNotNull().first() }
        val root = activity.getExternalFilesDir(null)!!
        previousFiles = listOf("photos", "results").flatMap { File(root, it).listFiles().orEmpty().toList() }
            .map { it.canonicalPath }.toSet()
        val journal = InspectionJournal(activity)
        previousRecords = journal.list().associate { it.id to journal.record(it.id).toString() }
        runBlocking {
            store.set(Keys.voiceEnabled, true).join()
            store.set(Keys.spokenFollowUp, false).join()
        }
        compose.waitUntil(10000) { store.state.value?.voiceEnabled == true && store.state.value?.spokenFollowUp == false }
    }

    private fun rememberOutput() {
        val model = compose.activity.fieldModel
        val root = compose.activity.getExternalFilesDir(null)!!
        val files = listOfNotNull(model.photo, model.reportName?.let { File(root, "results/$it") })
        ownFiles += files.filter { it.canonicalPath !in previousFiles && it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    @After
    fun restoreStateAndRemoveOwnOutputs() {
        val activity = compose.activity
        compose.runOnUiThread { activity.setContinuous(false); activity.voiceController.stop(); activity.resultSpeaker.stop() }
        idle()
        val journal = InspectionJournal(activity)
        val protectedPhotos = journal.list().map { File(it.photo).canonicalPath }.toSet()
        val protectedReports = journal.list().map { journal.record(it.id)!!.getString("report_name") }.toSet()
        for (file in ownFiles) {
            if (file.canonicalPath !in protectedPhotos && file.name !in protectedReports) {
                assertTrue("Own test output was not removed", !file.exists() || file.delete())
            }
        }
        val store = SettingsStore.get(activity)
        runBlocking {
            store.set(Keys.model, originalSettings.model).join()
            store.set(Keys.crop, originalSettings.crop).join()
            store.set(Keys.threshold, originalSettings.threshold).join()
            store.set(Keys.lastPhoto, originalSettings.lastPhoto).join()
            store.set(Keys.voiceEnabled, originalSettings.voiceEnabled).join()
            store.set(Keys.spokenFollowUp, originalSettings.spokenFollowUp).join()
            store.set(Keys.continuous, originalSettings.continuous).join()
        }
        compose.waitUntil(10000) { store.state.value == originalSettings }
        assertEquals(previousRecords, journal.list().associate { it.id to journal.record(it.id).toString() })
    }
    private fun idle(){compose.waitUntil(30000){!compose.activity.fieldModel.busy}}
    private fun say(text: String){compose.runOnUiThread {compose.activity.handleVoiceCommand(VoiceCommands.parse(text)!!)}}
    private fun listening(){compose.waitUntil(30000){compose.onAllNodesWithText("Слушаю постоянно…").fetchSemanticsNodes().isNotEmpty()}}
    private fun evidence(name: String,value: JSONObject){File(compose.activity.getExternalFilesDir(null),InstrumentationRegistry.getArguments().getString("voiceFolder") ?: "voice_validation_v3").apply {mkdirs()}.resolve(name).writeText(value.toString(2))}
    @Test fun rapidGallerySelectionUsesLatestPreviewAndClosingCancelsIt() {
        idle()
        say("открой галерею")
        compose.waitUntil(15000) { !compose.activity.photoFlow.loading && compose.activity.photoFlow.photos.size > 1 }
        say("первое фото")
        say("фото номер два")
        compose.waitUntil(15000) { compose.activity.photoFlow.preview != null && compose.activity.photoFlow.selectedNumber == 2 }
        val flow = compose.activity.photoFlow
        val expected = LocalPhotoGallery.preview(compose.activity, flow.photos[1].uri, 1080)
        try {
            assertTrue("An earlier preview overwrote the newest selection", flow.preview!!.sameAs(expected))
        } finally {
            expected.recycle()
        }
        say("назад")
        assertNull(flow.preview)
        say("открой галерею")
        compose.waitUntil(15000) { !flow.loading }
        assertNull(flow.selectedNumber)
        assertNull(flow.preview)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000) { !compose.activity.fieldModel.busy }
        assertEquals(com.agronomy.phytomonitor.actions.Screen.GALLERY, compose.activity.currentState().screen)
        assertNull(compose.activity.photoFlow.selectedNumber)
        say("отмена")
    }
    @Test fun continuousSwitchLastsBeyondOneShotTimeoutAndStopsInBackground() {
        idle();compose.runOnUiThread {compose.activity.setContinuous(false)}
        compose.onNodeWithTag("continuous_voice").performClick();listening()
        Thread.sleep(13500)
        assertTrue(compose.activity.voiceListening)
        assertTrue(compose.activity.photoFlow.continuousVoice)
        say("замолчи")
        assertTrue("Stopping speech must not stop an independently running microphone", compose.activity.voiceListening)
        compose.onNodeWithText("Сделать фото").assertIsEnabled()
        say("помощь");compose.onNodeWithText("Голосовые команды").assertIsDisplayed()
        say("понятно");compose.onNodeWithText("Голосовые команды").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(compose.activity.voiceListening)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);listening()
        compose.onNodeWithTag("continuous_voice").performClick()
        assertFalse(compose.activity.photoFlow.continuousVoice)
        assertFalse(compose.activity.voiceListening)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertFalse(compose.activity.voiceListening)
        evidence("continuous_flow.json",JSONObject().put("status","passed").put("held_ms",13500).put("manual_buttons_enabled",true)
            .put("background_stops_microphone",true).put("armed_foreground_resumes",true).put("off_does_not_resume",true).put("voice_help",true))
    }
    @Test fun twoStageCameraWaitsForConfirmationAndImmediateCameraDoesNot() {
        idle();compose.runOnUiThread {compose.activity.setContinuous(false)}
        val original=compose.activity.fieldModel.photo?.absolutePath
        val before=LocalPhotoGallery.load(compose.activity).map {it.id}.toSet()
        val captured = mutableListOf<Uri>()
        try {
            say("сделай фото")
            compose.waitUntil(30000){compose.onAllNodesWithText("Наведи телефон на лист и скажи «подтвердить»").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(30000){compose.onAllNodesWithText("Слушаю. Скажи одну команду…").fetchSemanticsNodes().isNotEmpty()}
            Thread.sleep(2200)
            assertTrue((compose.activity.currentState().screen == com.agronomy.phytomonitor.actions.Screen.CAMERA))
            assertEquals(before,LocalPhotoGallery.load(compose.activity).map {it.id}.toSet())
            say("подтвердить")
            compose.waitUntil(30000){!(compose.activity.currentState().screen == com.agronomy.phytomonitor.actions.Screen.CAMERA) && !compose.activity.fieldModel.busy && LocalPhotoGallery.load(compose.activity).any {it.id !in before}}
            val first=LocalPhotoGallery.load(compose.activity).single {it.id !in before && it.name.startsWith("AgroVoice_")}
            captured += first.uri
            rememberOutput()
            val afterFirst=LocalPhotoGallery.load(compose.activity).map {it.id}.toSet()
            say("сделай фото сразу")
            assertEquals(compose.activity.fieldModel.message, com.agronomy.phytomonitor.actions.Screen.CAMERA, compose.activity.currentState().screen)
            try {
                compose.waitUntil(30000){!(compose.activity.currentState().screen == com.agronomy.phytomonitor.actions.Screen.CAMERA) && !compose.activity.fieldModel.busy && LocalPhotoGallery.load(compose.activity).any {it.id !in afterFirst}}
            } catch (error: Throwable) {
                throw AssertionError("Immediate capture: ${compose.activity.cameraModel.state.value}; ${compose.activity.fieldModel.message}; ${compose.activity.currentState()}", error)
            }
            val second=LocalPhotoGallery.load(compose.activity).single {it.id !in afterFirst && it.name.startsWith("AgroVoice_")}
            captured += second.uri
            rememberOutput()
            assertTrue(first.name.startsWith("AgroVoice_") && second.name.startsWith("AgroVoice_"))
            val bytes=compose.activity.contentResolver.openInputStream(second.uri)!!.use {it.readBytes()}
            assertTrue(bytes.size>10000)
            assertArrayEquals(bytes,compose.activity.fieldModel.photo!!.readBytes())
            evidence("camera_flow.json",JSONObject().put("status","passed").put("no_photo_before_confirmation",true)
                .put("confirmed_photo",first.name).put("immediate_photo",second.name).put("immediate_bytes",bytes.size).put("shutter_button_clicks",0)
                .put("scope","Actual camera + dispatcher, unattended test bench; ASR is checked separately"))
        } finally {
            captured.forEach { assertEquals(1, compose.activity.contentResolver.delete(it,null,null)) }
            original?.let {compose.runOnUiThread {compose.activity.fieldModel.openFile(File(it), analyzePhoto=false)};idle()}
        }
    }
    @Test fun gallerySupportsVoiceButtonsSwipesAndExplicitConfirmation() {
        idle();compose.runOnUiThread {compose.activity.setContinuous(true)};listening()
        val original=compose.activity.fieldModel.photo?.absolutePath
        say("открой галерею")
        compose.waitUntil(15000){!compose.activity.photoFlow.loading && compose.activity.photoFlow.photos.size>2};listening()
        val photos=compose.activity.photoFlow.photos.toList()
        assertTrue(photos.zipWithNext().all {(a,b)->a.chronologicalMs>=b.chronologicalMs})
        say("первое фото");compose.waitUntil(15000){compose.activity.photoFlow.preview!=null}
        compose.onNodeWithText("Предыдущее").assertIsNotEnabled()
        say("следующее фото");compose.waitUntil(15000){compose.activity.photoFlow.preview!=null && compose.activity.photoFlow.selectedNumber==2}
        compose.onNodeWithTag("gallery_preview").performTouchInput {swipeLeft()}
        compose.waitUntil(15000){compose.activity.photoFlow.preview!=null && compose.activity.photoFlow.selectedNumber==3}
        compose.onNodeWithTag("gallery_preview").performTouchInput {swipeRight()}
        compose.waitUntil(15000){compose.activity.photoFlow.preview!=null && compose.activity.photoFlow.selectedNumber==2}
        compose.onNodeWithText("Следующее").performClick();compose.waitUntil(15000){compose.activity.photoFlow.preview!=null && compose.activity.photoFlow.selectedNumber==3}
        say("предыдущее фото");compose.waitUntil(15000){compose.activity.photoFlow.preview!=null && compose.activity.photoFlow.selectedNumber==2}
        say("последнее фото");compose.waitUntil(15000){compose.activity.photoFlow.preview!=null}
        assertEquals(photos.size,compose.activity.photoFlow.selectedNumber)
        compose.onNodeWithText("Следующее").assertIsNotEnabled()
        say("фото номер ${photos.size+1}");assertEquals(photos.size,compose.activity.photoFlow.selectedNumber)
        say("фото номер три");compose.waitUntil(15000){compose.activity.photoFlow.preview!=null}
        val selected=compose.activity.photoFlow.selected!!.uri
        assertEquals(original,compose.activity.fieldModel.photo?.absolutePath)
        assertEquals(photos,compose.activity.photoFlow.photos)
        say("подтвердить");idle();listening()
        rememberOutput()
        assertFalse((compose.activity.currentState().screen == com.agronomy.phytomonitor.actions.Screen.GALLERY))
        assertArrayEquals(compose.activity.contentResolver.openInputStream(selected)!!.use {it.readBytes()},compose.activity.fieldModel.photo!!.readBytes())
        say("стоп");assertFalse(compose.activity.photoFlow.continuousVoice);assertFalse(compose.activity.voiceListening)
        evidence("gallery_flow.json",JSONObject().put("status","passed").put("photos",photos.size).put("stable_newest_to_oldest",true)
            .put("swipes_and_buttons",true).put("voice_next_previous",true).put("bounds",true).put("confirmation_only_import",true).put("continuous_across_screens",true))
        original?.let {compose.runOnUiThread {compose.activity.fieldModel.openFile(File(it), analyzePhoto=false)};idle()}
    }
    @Test fun voiceControlsAllEightModelsCulturesThresholdNavigationAndHelp() {
        idle();compose.runOnUiThread {compose.activity.setContinuous(false)}
        val model=compose.activity.fieldModel;val oldModel=model.selectedModel;val oldCrop=model.crop;val oldThreshold=model.threshold
        say("открой галерею");compose.waitUntil(15000){!compose.activity.photoFlow.loading}
        compose.runOnUiThread {compose.activity.setContinuous(false)}
        VoiceCommands.modelIds.forEachIndexed {index,id->say("модель ${RussianPhotoNumber.words(index+1)}");assertEquals(id,model.selectedModel)}
        listOf("картофель" to "potato","томаты" to "tomato","огурцы" to "cucumber","все культуры" to "all").forEach {(text,id)->say(text);assertEquals(id,model.crop)}
        say("порог двадцать пять процентов");assertEquals(0.25f,model.threshold,0.001f)
        say("увеличь порог");assertEquals(0.30f,model.threshold,0.001f)
        say("уменьши порог");assertEquals(0.25f,model.threshold,0.001f)
        say("порог восемьдесят");say("увеличь порог");assertEquals(0.8f,model.threshold,0.001f)
        say("порог десять");say("уменьши порог");assertEquals(0.1f,model.threshold,0.001f)
        val before=compose.activity.photoFlow.scrollEvent;say("в конец");assertEquals(before+1,compose.activity.photoFlow.scrollEvent)
        say("помощь");assertTrue(compose.activity.photoFlow.helpOpen);say("назад");assertFalse(compose.activity.photoFlow.helpOpen)
        say("назад");assertFalse((compose.activity.currentState().screen == com.agronomy.phytomonitor.actions.Screen.GALLERY))
        compose.runOnUiThread {model.setModel(oldModel,false);model.selectCrop(oldCrop,false);model.changeThreshold(oldThreshold)}
        evidence("controls_flow.json",JSONObject().put("status","passed").put("models",VoiceCommands.modelIds.size).put("cultures",4).put("threshold_range","10..80 step5").put("help_back_scroll",true))
    }
}
