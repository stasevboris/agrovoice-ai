package com.agronomy.phytomonitor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.agronomy.phytomonitor.journal.FrameRole
import com.agronomy.phytomonitor.journal.InspectionFrame
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.journal.JournalArchive
import com.agronomy.phytomonitor.journal.JournalRecords
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Injected commands exercise the real Activity/Room/export path, not live recognition or the system picker. */
class FrameFeedbackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test
    fun selectedFrameFeedbackAndPrivateExportSurviveArchive() {
        val context = compose.activity
        check(context.packageName.endsWith(".verification")) { "Run only in the isolated verification installation." }
        compose.waitUntil(60000) { !context.currentState().busy }
        val journal = InspectionJournal(context)
        val fixtureId = "feedback-device-${UUID.randomUUID()}"
        val base = context.getExternalFilesDir(null)!!
        val evidence = File(base, "feedback_verification/$fixtureId").apply { mkdirs() }
        val created = mutableListOf<File>()
        val frames = (0..2).map { index ->
            val photo = File(base, "photos/$fixtureId-$index.jpg").apply { parentFile!!.mkdirs() }
            created += photo
            val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(photo).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_ARTIST, "PRIVATE_FIXTURE_ARTIST")
                setLatLong(53.9, 27.5)
                saveAttributes()
            }
            val report = File(base, "results/$fixtureId-$index.json").apply {
                parentFile!!.mkdirs()
                writeText(JSONObject().put("photo", photo.path).put("photo_sha256", InspectionJournal.sha256(photo))
                    .put("model", "synthetic-fixture").put("model_sha256", "a".repeat(64))
                    .put("decoded_width", 20).put("decoded_height", 30).put("detections", JSONArray())
                    .put("note", "PRIVATE_FIXTURE_PREDICTION").put("lat", 53.9)
                    .put("agrovoice_sync_source", JSONObject().put("account", "PRIVATE_FIXTURE_ACCOUNT"))
                    .put("result_provenance", JSONObject().put("schema_version", 1).put("taxonomy_version", "unspecified"))
                    .toString(2))
            }
            created += report
            InspectionFrame("$fixtureId-frame-$index", report, FrameRole.FRONT)
        }
        val database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).build()
        // Injected commands go through the voice path, which obeys the user's «voice actions» switch.
        val settings = SettingsStore.get(context)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        runBlocking { settings.set(Keys.voiceEnabled, true).join() }
        try {
            journal.saveSeries(fixtureId, frames, "PRIVATE_FIXTURE_NOTE", "uncertain", "TEST: PRIVATE_FIXTURE_PLOT")
            val before = journal.details(fixtureId)!!
            val hashes = created.associate { it.path to InspectionJournal.sha256(it) }
            say("открой журнал")
            compose.waitUntil(10000) { context.currentState().screen == com.agronomy.phytomonitor.actions.Screen.JOURNAL }
            compose.runOnUiThread { context.journalModel.refresh() }
            compose.waitUntil(30000) { !context.currentState().busy && context.journalModel.state.value.entries.any { it.id == fixtureId } }
            val number = context.journalModel.state.value.visible.indexOfFirst { it.id == fixtureId } + 1
            // Speech grammar uses number words, not digits.
            val words = listOf("один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять")
            check(number in 1..words.size) { "Fixture is not among the first visible inspections: $number" }
            say("открой осмотр ${words[number - 1]}")
            compose.waitUntil(30000) { context.journalModel.state.value.opened?.details?.inspection?.id == fixtureId }
            say("кадр два")
            compose.waitUntil(30000) { !context.currentState().busy && context.journalModel.state.value.opened?.selectedFrame == 2 }
            say("исправление кадра другая культура")
            compose.waitUntil(30000) { !context.currentState().busy && context.journalModel.state.value.opened?.activePhoto?.feedback != null }
            val after = journal.details(fixtureId)!!
            assertNull(after.photos[0].feedback)
            assertEquals("другая культура", after.photos[1].feedback!!.correction)
            assertEquals(1, after.photos[1].feedback!!.analysisVersion)
            assertEquals("user", after.photos[1].feedback!!.source)
            assertEquals(before.inspection.predictionJson, after.inspection.predictionJson)
            assertEquals(before.photos.map { it.photo }, after.photos.map { it.photo })
            assertEquals(hashes, created.associate { it.path to InspectionJournal.sha256(it) })
            say("выбери для экспорта кадр один")
            say("выбери для экспорта кадр два")
            say("экспорт выбранных кадров")
            compose.waitUntil(10000) { context.journalModel.state.value.pendingFeedbackExport.size == 2 }
            compose.onNodeWithText("Сохранить выбранные кадры?").assertIsDisplayed()
            val selected = context.journalModel.state.value.pendingFeedbackExport.toList()
            say("отмена")
            compose.waitUntil(10000) { context.journalModel.state.value.pendingFeedbackExport.isEmpty() }
            val exported = File(evidence, "selected.zip")
            compose.runOnUiThread { context.journalModel.exportFeedback(Uri.fromFile(exported), selected) }
            compose.waitUntil(30000) { !context.journalModel.state.value.busy && exported.isFile }
            assertFalse(context.journalModel.state.value.message.orEmpty(), context.journalModel.state.value.messageIsError)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(exported.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertEquals(setOf("manifest.json", "frames/1.png", "frames/2.png"), entries.keys)
            val text = entries.getValue("manifest.json").toString(Charsets.UTF_8)
            assertFalse(text.contains("PRIVATE_FIXTURE"))
            assertFalse(text.contains(base.path))
            val manifest = JSONObject(text).getJSONArray("frames")
            for (index in 0 until manifest.length()) {
                val frame = manifest.getJSONObject(index)
                val png = File(evidence, "frame-${index + 1}.png").apply { writeBytes(entries.getValue(frame.getString("image"))) }
                assertNull(ExifInterface(png).latLong)
                assertNull(ExifInterface(png).getAttribute(ExifInterface.TAG_ARTIST))
                assertEquals(InspectionJournal.sha256(png), frame.getString("export_photo_sha256"))
                assertEquals(after.photos[index].photo.sha256, frame.getString("original_photo_sha256"))
            }
            val archive = File(evidence, "roundtrip.zip")
            // Only this synthetic record: unrelated verification fixtures may intentionally lack photos.
            val names = after.photos.associate { frame -> frame.photo.id to
                JournalArchive.photoEntry(JournalRecords.photoFileId(frame.photo.id), File(frame.photo.localPath)) }
            val archivePhotos = after.photos.associate { names.getValue(it.photo.id) to File(it.photo.localPath) }
            val document = JournalRecords.document(JSONArray().put(JournalRecords.toJson(after, archivePhotos = names)),
                containsPhotos = true, exportedAt = System.currentTimeMillis())
            archive.outputStream().use { JournalArchive.write(it, document.toString(), archivePhotos) }
            val restoreRoot = File(context.cacheDir, fixtureId)
            val isolated = object : ContextWrapper(context) {
                override fun getExternalFilesDir(type: String?) = File(restoreRoot, "files").apply { mkdirs() }
                override fun getFilesDir() = getExternalFilesDir(null)
                override fun getCacheDir() = File(restoreRoot, "cache").apply { mkdirs() }
            }
            val restored = InspectionJournal(isolated, database)
            val report = archive.inputStream().use(restored::import)
            assertTrue(report.issues.toString(), report.issues.isEmpty())
            val copy = restored.details(fixtureId)!!
            assertEquals(after.photos.map { it.feedback }, copy.photos.map { it.feedback })
            assertEquals(after.photos.map { it.photo.predictionJson }, copy.photos.map { it.photo.predictionJson })
            File(evidence, "report.json").writeText(JSONObject().put("status", "passed")
                .put("synthetic", true).put("selected_frames", manifest.length()).put("original_frames", frames.size)
                .put("evidence_unchanged", true).put("exif_removed", true).put("archive_feedback_preserved", true)
                .put("scope", "Activity injected commands, selected-frame Room feedback, privacy dialog/cancel, ViewModel file URI ZIP, archive restore; no live speech or system document picker interaction")
                .toString(2))
        } finally {
            runBlocking { settings.set(Keys.voiceEnabled, previous.voiceEnabled).join() }
            database.close()
            journal.deleteForTest(fixtureId)
            created.forEach { it.delete() }
        }
    }

    private fun say(text: String) {
        compose.runOnUiThread {
            val command = requireNotNull(VoiceCommands.parseForScreen(text, compose.activity.currentState().screen.protocolName)) { text }
            compose.activity.handleVoiceCommand(command)
        }
        compose.waitForIdle()
    }
}
