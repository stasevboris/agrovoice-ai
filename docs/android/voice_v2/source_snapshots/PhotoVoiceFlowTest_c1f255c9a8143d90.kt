package com.agronomy.phytomonitor

import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.agronomy.phytomonitor.photos.LocalPhotoGallery
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real gallery and real camera. Dispatcher test is distinct from acoustic recognition tests. */
class PhotoVoiceFlowTest {
    @get:Rule val compose=createAndroidComposeRule<FieldDemoActivity>()
    private fun idle(){compose.waitUntil(30000){!compose.activity.fieldModel.busy}}
    private fun say(text: String){compose.runOnUiThread {compose.activity.handleVoiceCommand(VoiceCommands.parse(text)!!)}}
    private fun evidence(name: String,value: JSONObject) {
        File(compose.activity.getExternalFilesDir(null),"voice_validation_v2").apply {mkdirs()}.resolve(name).writeText(value.toString(2))
    }
    @Test fun galleryHasStableChronologicalNumbersAndRequiresConfirmation() {
        idle()
        val original=compose.activity.fieldModel.photo?.absolutePath
        say("открой галерею")
        compose.waitUntil(15000){!compose.activity.photoFlow.loading && compose.activity.photoFlow.photos.isNotEmpty()}
        val photos=compose.activity.photoFlow.photos.toList()
        assertTrue(photos.zipWithNext().all {(a,b)->a.chronologicalMs>=b.chronologicalMs})
        say("подтвердить")
        assertTrue(compose.activity.photoFlow.galleryOpen)
        assertEquals(original,compose.activity.fieldModel.photo?.absolutePath)
        say("первое фото")
        compose.waitUntil(15000){compose.activity.photoFlow.preview!=null}
        assertEquals(1,compose.activity.photoFlow.selectedNumber)
        assertEquals(photos.first().uri,compose.activity.photoFlow.selected!!.uri)
        say("последнее фото")
        compose.waitUntil(15000){compose.activity.photoFlow.preview!=null}
        assertEquals(photos.size,compose.activity.photoFlow.selectedNumber)
        assertEquals(photos.last().uri,compose.activity.photoFlow.selected!!.uri)
        val desired=minOf(3,photos.size)
        say("фото номер $desired")
        compose.waitUntil(15000){compose.activity.photoFlow.preview!=null}
        val uri=compose.activity.photoFlow.selected!!.uri
        assertEquals(desired,compose.activity.photoFlow.selectedNumber)
        assertEquals(original,compose.activity.fieldModel.photo?.absolutePath)
        say("фото номер ${photos.size+1}")
        assertEquals(desired,compose.activity.photoFlow.selectedNumber)
        assertEquals(photos,compose.activity.photoFlow.photos)
        compose.waitUntil(15000){compose.onAllNodesWithText("Слушаю. Скажи одну команду…").fetchSemanticsNodes().isNotEmpty()}
        say("подтвердить")
        idle()
        assertFalse(compose.activity.photoFlow.galleryOpen)
        assertFalse(compose.activity.photoFlow.dialogVoice)
        val imported=compose.activity.fieldModel.photo!!
        val expected=compose.activity.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
        assertArrayEquals(expected,imported.readBytes())
        compose.onNodeWithText("Голосовая команда").assertIsEnabled()
        evidence("gallery_flow.json",JSONObject().put("status","passed").put("photos",photos.size)
            .put("newest",photos.first().name).put("oldest",photos.last().name).put("selected_number",desired)
            .put("import_only_after_confirmation",true).put("stable_numbers",true).put("bounds_check",true)
            .put("import_bytes_equal",true).put("scope","Real MediaStore + preview + voice dispatcher, acoustic decoder tested separately"))
        original?.let {path->compose.runOnUiThread {compose.activity.fieldModel.openFile(File(path))};idle()}
    }
    @Test fun voiceCameraActuallySavesJpegWithoutShutterClick() {
        idle()
        val original=compose.activity.fieldModel.photo?.absolutePath
        val before=LocalPhotoGallery.load(compose.activity).map {it.id}.toSet()
        say("сделай фото")
        compose.waitUntil(30000){!compose.activity.photoFlow.cameraOpen && !compose.activity.fieldModel.busy && compose.activity.fieldModel.photo?.name?.startsWith("import_")==true && LocalPhotoGallery.load(compose.activity).any {it.id !in before && it.name.startsWith("AgroVoice_")}}
        val captured=LocalPhotoGallery.load(compose.activity).first {it.id !in before && it.name.startsWith("AgroVoice_")}
        assertEquals(captured.id,LocalPhotoGallery.load(compose.activity).first().id)
        assertTrue(compose.activity.fieldModel.bitmap!!.width>0)
        val bytes=compose.activity.contentResolver.openInputStream(captured.uri)!!.use {it.readBytes()}
        assertArrayEquals(bytes,compose.activity.fieldModel.photo!!.readBytes())
        evidence("camera_flow.json",JSONObject().put("status","passed").put("photo",captured.name)
            .put("bytes",bytes.size).put("shutter_button_clicks",0).put("newest_in_gallery",true)
            .put("width",compose.activity.fieldModel.bitmap!!.width).put("height",compose.activity.fieldModel.bitmap!!.height)
            .put("scope","Real Note7 rear camera; scene is the unattended test bench, not plant diagnosis quality"))
        // Remove only this newly created test shot, and restore the user's previous working photo.
        assertEquals(1,compose.activity.contentResolver.delete(captured.uri,null,null))
        original?.let {path->compose.runOnUiThread {compose.activity.fieldModel.openFile(File(path))};idle()}
    }
    @Test fun galleryListeningStopsOnBackgroundAndDoesNotResumeAutomatically() {
        idle();say("открой галерею")
        compose.waitUntil(20000){compose.onAllNodesWithText("Слушаю. Скажи одну команду…").fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertFalse(compose.activity.photoFlow.dialogVoice)
        compose.onNodeWithText("Голосовая команда").assertIsEnabled()
        say("отмена")
        assertFalse(compose.activity.photoFlow.galleryOpen)
    }
}
