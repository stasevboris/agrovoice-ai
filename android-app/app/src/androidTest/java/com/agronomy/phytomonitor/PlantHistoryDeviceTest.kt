package com.agronomy.phytomonitor

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic owned photos, real save/navigation, no modification of existing inspections. */
class PlantHistoryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    private fun say(text: String) = compose.runOnUiThread {
        val activity = compose.activity
        activity.handleVoiceCommand(VoiceCommands.parseForScreen(text, activity.currentState().screen.protocolName)!!)
    }

    private fun idle() = compose.waitUntil(60000) { !compose.activity.currentState().busy }

    @Test fun savePlantCompareDatesEditNoteAndReturnWithoutChangingExistingRecords() {
        idle()
        val activity = compose.activity
        val model = activity.fieldModel
        val settings = SettingsStore.get(activity)
        val previous = requireNotNull(settings.state.value)
        val journal = InspectionJournal(activity)
        val awardsBefore = JournalDatabase.get(activity).journal().achievements()
        val original = journal.list().associate { it.id to journal.record(it.id).toString() }
        val photo = File.createTempFile("plant-history-", ".png", activity.cacheDir)
        val reports = mutableListOf<File>()
        val ownIds = mutableListOf<String>()
        var plantId: String? = null
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            runBlocking { settings.set(Keys.achievements, false).join() }
            compose.runOnUiThread {
                activity.setContinuous(false)
                model.editPlot("TEST: история ${System.nanoTime()}")
                model.openFile(photo)
            }
            compose.waitUntil(60000) { !model.busy && model.reportName != null }
            compose.runOnUiThread { model.editNote("TEST: первый осмотр") }
            reports += File(activity.getExternalFilesDir(null), "results/${model.reportName}")
            say("растение куст семь")
            assertEquals("куст семь", model.plantName)
            say("сохрани осмотр")
            compose.waitUntil(60000) { model.inspectionSaved && !model.busy }
            assertEquals(awardsBefore, JournalDatabase.get(activity).journal().achievements())
            val first = journal.list().single { it.id !in original }
            ownIds += first.id
            plantId = first.plantId
            assertNotNull(plantId)
            assertEquals("куст семь", first.plant)
            val secondReport = File.createTempFile("plant-history-second-", ".json", activity.cacheDir)
            reports += secondReport
            secondReport.writeText(reports.first().readText())
            val later = InspectionJournal(activity, JournalDatabase.get(activity)) { first.created + 86400000 }
            later.achievementsEnabled = false
            val second = later.save(secondReport, "TEST: повторный осмотр", "unrated", first.plot, plant = "Куст 7")
            ownIds += second.id
            assertEquals(plantId, second.plantId)
            say("открой историю растения")
            compose.waitUntil(30000) { activity.currentState().plantHistoryOpen && activity.journalModel.state.value.comparison.size == 2 && !activity.currentState().busy }
            assertEquals(ownIds, activity.journalModel.state.value.history.map { it.id })
            say("сравни осмотры один и два")
            idle()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30000) { compose.activity.currentState().plantHistoryOpen && compose.activity.journalModel.state.value.comparison.size == 2 && !compose.activity.currentState().busy }
            say("открой осмотр два")
            compose.waitUntil(30000) { compose.activity.journalModel.state.value.opened?.details?.inspection?.id == second.id }
            compose.onNodeWithText("Изменить заметку").performScrollTo().performClick()
            compose.runOnUiThread { compose.activity.journalModel.editNote("TEST: исправленная заметка") }
            say("подтвердить")
            compose.waitUntil(30000) { !compose.activity.fieldModel.noteOpen && !compose.activity.currentState().busy }
            assertEquals("TEST: исправленная заметка", journal.details(second.id)!!.inspection.note)
            assertEquals(plantId, journal.details(second.id)!!.photos.single().photo.plantId)
            say("назад")
            compose.waitUntil(30000) { compose.activity.currentState().plantHistoryOpen && !compose.activity.currentState().busy }
            say("назад")
            compose.waitUntil(10000) { compose.activity.currentState().screen == Screen.MAIN }
            val evidence = File(activity.getExternalFilesDir(null), "plant_preview_check").apply { mkdirs() }
            File(evidence, "plant_history.json").writeText(JSONObject().put("status", "passed")
                .put("synthetic_owned_inspections", ownIds.size).put("stable_plant_id", true)
                .put("recreation", true).put("saved_note", true)
                .put("scope", "Real device, command injection and Compose buttons; not live ASR or agronomic evaluation.").toString(2))
        } finally {
            compose.runOnUiThread {
                compose.activity.setContinuous(false)
                compose.activity.resultSpeaker.stop()
            }
            idle()
            // Only IDs created by this test, never original records.
            val created = journal.list().filter { it.id !in original && it.plot.startsWith("TEST: история ") &&
                journal.record(it.id)?.optString("report_name") in reports.map { report -> report.name } }
            created.forEach { journal.deleteForTest(it.id) }
            plantId?.let { id ->
                JournalDatabase.get(activity).openHelper.writableDatabase.execSQL(
                    "DELETE FROM plant WHERE id = ? AND NOT EXISTS (SELECT 1 FROM photo WHERE plant_id = ?)", arrayOf(id, id))
            }
            runBlocking {
                settings.set(Keys.plot, previous.plot).join()
                settings.set(Keys.lastPhoto, previous.lastPhoto).join()
                settings.set(Keys.continuous, previous.continuous).join()
                settings.set(Keys.achievements, previous.achievements).join()
            }
            reports.forEach { assertTrue(!it.exists() || it.delete()) }
            assertTrue(photo.delete())
            assertTrue("Original records changed", original.all { (id, record) -> journal.record(id).toString() == record })
        }
    }

    @Test fun previewCanBeDisabledAndLeavingCameraStopsAnalysis() {
        idle()
        val activity = compose.activity
        val previous = requireNotNull(SettingsStore.get(activity).state.value)
        val photos = File(activity.getExternalFilesDir(null), "photos").list()?.toSet().orEmpty()
        try {
            say("открой камеру")
            compose.waitUntil(30000) { activity.cameraModel.state.value.qualitySamples >= 2 }
            assertNotNull(activity.cameraModel.state.value.qualityVerdict)
            say("выключи подсказки кадра")
            assertFalse(activity.cameraModel.state.value.guidanceEnabled)
            val samples = activity.cameraModel.state.value.qualitySamples
            val until = android.os.SystemClock.elapsedRealtime() + 1600
            compose.waitUntil(3000) { android.os.SystemClock.elapsedRealtime() >= until }
            assertEquals(samples, activity.cameraModel.state.value.qualitySamples)
            say("включи подсказки кадра")
            compose.waitUntil(5000) { activity.cameraModel.state.value.qualitySamples > samples }
            say("назад")
            compose.waitUntil(5000) { activity.currentState().screen == Screen.MAIN }
            val after = android.os.SystemClock.elapsedRealtime() + 1600
            compose.waitUntil(3000) { android.os.SystemClock.elapsedRealtime() >= after }
            assertEquals(0, activity.cameraModel.state.value.qualitySamples)
            assertEquals(photos, File(activity.getExternalFilesDir(null), "photos").list()?.toSet().orEmpty())
        } finally {
            compose.runOnUiThread { activity.setContinuous(false) }
            runBlocking { SettingsStore.get(activity).set(Keys.continuous, previous.continuous).join() }
        }
    }
}
