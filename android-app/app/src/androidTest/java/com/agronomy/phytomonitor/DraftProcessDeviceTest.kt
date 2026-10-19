package com.agronomy.phytomonitor

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.draft.InspectionDraft
import com.agronomy.phytomonitor.draft.InspectionDraftStore
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Run methods separately, with force-stop between phases. Only owned fixtures are removed. */
@RunWith(AndroidJUnit4::class)
class DraftProcessDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "draft-device-check")
    private val draftFile = File(context.filesDir, "inspection-draft.json")
    private val store get() = InspectionDraftStore(draftFile)
    private val marker get() = File(directory, "state.json")
    private val evidenceFile get() = File(context.getExternalFilesDir(null), "draft_device_report.json")

    @Test
    fun prepareInterruptedInspection() {
        check(!marker.exists()) { "Finish cleanup of the preceding draft test before preparing again" }
        directory.mkdirs()
        val settings = SettingsStore.get(context)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        val baseline = JSONObject().put("draft_existed", draftFile.exists())
            .put("plot", previous.plot).put("row", previous.row).put("last_photo", previous.lastPhoto)
            .put("continuous", previous.continuous).put("voice_enabled", previous.voiceEnabled)
            .put("spoken_follow_up", previous.spokenFollowUp).put("achievements", previous.achievements)
            .put("location", previous.location)
        val fixturePlot = "TEST: черновик процесса ${UUID.randomUUID()}"
        check(JournalDatabase.get(context).journal().plotByName(fixturePlot) == null)
        baseline.put("fixture_plot", fixturePlot)
        val journal = InspectionJournal(context)
        baseline.put("original_records", JSONObject().also { records ->
            journal.list().forEach { records.put(it.id, journal.record(it.id).toString()) }
        })
        if (draftFile.exists()) draftFile.copyTo(File(directory, "original-draft.json"), overwrite = true)
        marker.writeText(baseline.toString(), Charsets.UTF_8)
        runBlocking {
            settings.set(Keys.continuous, false).join()
            settings.set(Keys.voiceEnabled, true).join()
            settings.set(Keys.spokenFollowUp, false).join()
            settings.set(Keys.achievements, false).join()
            settings.set(Keys.location, false).join()
        }
        val photo = File(directory, "synthetic.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(70, 120, 60))
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        store.write(InspectionDraft(photo = photo.path, model = previous.model))
        val evidence = JSONObject().put("scope", "Physical Android; injected commands and synthetic solid-color frame, not live speech, field diagnosis or user study")
            .put("timing_clock", "SystemClock.elapsedRealtime milliseconds; polling at 25 ms")
            .put("fixture", "Owned 64x64 RGB(70,120,60) PNG, no agronomic label")
        val launchAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch(FieldDemoActivity::class.java).use { scenario ->
            await(scenario) { !it.fieldModel.busy && it.fieldModel.hasDraft }
            evidence.put("activity_launch_to_model_ready_ms", SystemClock.elapsedRealtime() - launchAt)
            scenario.onActivity { say(it, "продолжить осмотр") }
            await(scenario) { !it.fieldModel.busy && !it.fieldModel.hasDraft }
            val analysisAt = SystemClock.elapsedRealtime()
            scenario.onActivity { it.fieldModel.openFile(photo) }
            await(scenario) { !it.fieldModel.busy && it.fieldModel.result != null }
            evidence.put("open_file_to_result_ms", SystemClock.elapsedRealtime() - analysisAt)
            scenario.onActivity {
                it.fieldModel.editPlot(fixturePlot)
                it.fieldModel.editRow("14")
                it.fieldModel.editPlant("TEST: куст 7")
                it.fieldModel.editNote("TEST: заметка после анализа")
            }
            await(scenario) { !it.fieldModel.draftSaving }
            waitFor { store.read()?.note == "TEST: заметка после анализа" }
            val saved = requireNotNull(store.read())
            File(directory, "expected-draft.json").writeText(saved.toJson().toString(), Charsets.UTF_8)
            baseline.put("expected_draft_id", saved.id)
            marker.writeText(baseline.toString(), Charsets.UTF_8)
            scenario.recreate()
            await(scenario) { !it.fieldModel.busy }
            scenario.onActivity { assertDraft(it.fieldModel, saved) }
            evidence.put("activity_recreation", "passed")
        }
        evidence.put("prepared_pid", android.os.Process.myPid()).put("phase", "prepared")
        evidenceFile.writeText(evidence.toString(2), Charsets.UTF_8)
    }

    @Test
    fun restoreAfterProcessRestartAndSaveOnce() {
        val expected = InspectionDraft.fromJson(JSONObject(File(directory, "expected-draft.json").readText()))
        val evidence = JSONObject(evidenceFile.readText())
        assertNotEquals("Run force-stop between instrumentation phases", evidence.getInt("prepared_pid"), android.os.Process.myPid())
        val journal = InspectionJournal(context)
        ActivityScenario.launch(FieldDemoActivity::class.java).use { scenario ->
            await(scenario) { !it.fieldModel.busy && it.fieldModel.hasDraft }
            val resumeAt = SystemClock.elapsedRealtime()
            scenario.onActivity { say(it, "продолжить осмотр") }
            await(scenario) { !it.fieldModel.busy && !it.fieldModel.hasDraft }
            evidence.put("resume_draft_to_result_ms", SystemClock.elapsedRealtime() - resumeAt)
            scenario.onActivity { assertDraft(it.fieldModel, expected) }
            val savingAt = SystemClock.elapsedRealtime()
            scenario.onActivity { say(it, "сохрани осмотр") }
            await(scenario) { !it.fieldModel.busy && it.fieldModel.inspectionSaved }
            val saved = requireNotNull(JournalDatabase.get(context).journal().inspectionByReport(expected.reportName!!))
            evidence.put("save_request_to_confirmed_room_ms", SystemClock.elapsedRealtime() - savingAt)
            scenario.onActivity { say(it, "сохранить осмотр") }
            assertEquals(1, journal.list().count { it.id == saved.id })
            val baseline = JSONObject(marker.readText()).put("created_id", saved.id)
            marker.writeText(baseline.toString(), Charsets.UTF_8)
            journal.editNote(saved.id, saved.updatedAt, "TEST: более новая заметка после сохранения")
        }
        // Restore the pre-commit marker to emulate death after Room commits but before clearing it.
        store.write(expected.copy(saveRequested = true, savedBeforeVersion = null))
        evidence.put("restored_pid", android.os.Process.myPid()).put("process_restart", "passed")
            .put("repeat_save", "one_record").put("phase", "committed_marker_prepared")
        evidenceFile.writeText(evidence.toString(2), Charsets.UTF_8)
    }

    @Test
    fun recoverLostSaveResponseAndCleanUp() {
        val evidence = JSONObject(evidenceFile.readText())
        assertNotEquals(evidence.getInt("restored_pid"), android.os.Process.myPid())
        try {
            ActivityScenario.launch(FieldDemoActivity::class.java).use { scenario ->
                await(scenario) { !it.fieldModel.busy }
                scenario.onActivity { assertFalse(it.fieldModel.hasDraft) }
                val journal = InspectionJournal(context)
                val id = JSONObject(marker.readText()).getString("created_id")
                assertEquals("TEST: более новая заметка после сохранения", journal.details(id)!!.inspection.note)
                assertNull(store.read())
                evidence.put("lost_save_response", "passed_without_replay_or_note_rollback")
            }
        } finally {
            restoreOriginalState()
        }
        evidence.put("phase", "complete").put("original_records_and_settings", "restored")
        evidenceFile.writeText(evidence.toString(2), Charsets.UTF_8)
    }

    /** Emergency cleanup also works after a failed phase. Original backup files remain available. */
    @Test
    fun cleanUpInterruptedTest() {
        restoreOriginalState()
    }

    @Test
    fun corruptDraftCanStartNewInspectionWithoutLosingOriginalBytes() {
        val ownDirectory = File(context.filesDir, "draft-corrupt-check-${UUID.randomUUID()}").apply { mkdirs() }
        val original = draftFile.takeIf { it.exists() }?.readBytes()
        original?.let { File(ownDirectory, "original-draft.bin").writeBytes(it) }
        val previous = runBlocking { SettingsStore.get(context).state.filterNotNull().first() }
        val settings = SettingsStore.get(context)
        val originalRecords = InspectionJournal(context).let { journal ->
            journal.list().associate { it.id to journal.record(it.id).toString() }
        }
        val recoveryBefore = context.filesDir.listFiles().orEmpty().filter {
            it.name.startsWith("inspection-draft.json.recovery-")
        }.map { it.name }.toSet()
        val photo = File(ownDirectory, "synthetic.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            runBlocking {
                settings.set(Keys.continuous, false).join()
                settings.set(Keys.voiceEnabled, true).join()
                settings.set(Keys.spokenFollowUp, false).join()
                settings.set(Keys.location, false).join()
            }
            draftFile.writeText("{damaged", Charsets.UTF_8)
            ActivityScenario.launch(FieldDemoActivity::class.java).use { scenario ->
                await(scenario) { !it.fieldModel.busy }
                scenario.onActivity {
                    assertTrue("Unreadable draft must offer a recovery action", it.fieldModel.hasDraft)
                    assertNotNull(it.fieldModel.draftError)
                    say(it, "начать новый осмотр")
                }
                await(scenario) { !it.fieldModel.busy && !it.fieldModel.hasDraft }
                scenario.onActivity {
                    assertNull(it.fieldModel.draftError)
                    it.fieldModel.openFile(photo, analyzePhoto = false)
                }
                await(scenario) { !it.fieldModel.busy && !it.fieldModel.draftSaving && it.fieldModel.photo == photo }
                waitFor { store.read()?.photo == photo.path }
            }
            val recovery = context.filesDir.listFiles().orEmpty().single {
                it.name.startsWith("inspection-draft.json.recovery-") && it.name !in recoveryBefore
            }
            assertEquals("{damaged", recovery.readText())
            File(ownDirectory, "report.json").writeText(JSONObject()
                .put("status", "passed").put("corrupt_bytes_preserved", true)
                .put("new_inspection_opened", true)
                .put("scope", "Injected command, synthetic PNG, no inference or live ASR").toString(2))
        } finally {
            restoreOriginalDraft(original)
            runBlocking {
                settings.set(Keys.continuous, previous.continuous).join()
                settings.set(Keys.voiceEnabled, previous.voiceEnabled).join()
                settings.set(Keys.spokenFollowUp, previous.spokenFollowUp).join()
                settings.set(Keys.location, previous.location).join()
                settings.set(Keys.lastPhoto, previous.lastPhoto).join()
            }
            val journal = InspectionJournal(context)
            assertEquals(originalRecords, journal.list().associate { it.id to journal.record(it.id).toString() })
        }
    }

    private fun restoreOriginalState() {
        if (!marker.exists()) return
        val baseline = JSONObject(marker.readText())
        val journal = InspectionJournal(context)
        val expectedFile = File(directory, "expected-draft.json")
        val expected = if (expectedFile.exists()) InspectionDraft.fromJson(JSONObject(expectedFile.readText())) else null
        val restoration = store.writer()
        restoration.withOwnership {
            val database = JournalDatabase.get(context)
            val thisRun = expected?.takeIf {
                it.id == baseline.optString("expected_draft_id") && it.plot == baseline.optString("fixture_plot")
            }
            val created = thisRun?.reportName?.let { database.journal().inspectionByReport(it) }
            if (created != null) {
                val ownedDraft = requireNotNull(thisRun)
                check(created.note.startsWith("TEST:"))
                val plants = journal.details(created.id)!!.photos.mapNotNull { it.photo.plantId }.distinct()
                journal.deleteForTest(created.id)
                if (ownedDraft.plot == baseline.optString("fixture_plot")) {
                    // This unique plot did not exist before prepare; delete only its now-unreferenced fixture plant IDs.
                    plants.forEach { plantId ->
                        database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM plant WHERE id = ? AND name = ? AND NOT EXISTS (SELECT 1 FROM photo WHERE plant_id = ?)",
                            arrayOf(plantId, ownedDraft.plant, plantId))
                    }
                }
            }
            val originalRecords = baseline.getJSONObject("original_records")
            originalRecords.keys().forEach { id -> assertEquals(originalRecords.getString(id), journal.record(id).toString()) }
            val original = if (baseline.getBoolean("draft_existed")) File(directory, "original-draft.json").readBytes() else null
            restoreOriginalDraft(original, restoration)
        }
        val settings = SettingsStore.get(context)
        runBlocking {
            settings.set(Keys.plot, baseline.getString("plot")).join()
            settings.set(Keys.row, baseline.getString("row")).join()
            settings.set(Keys.lastPhoto, if (baseline.isNull("last_photo")) null else baseline.getString("last_photo")).join()
            settings.set(Keys.continuous, baseline.getBoolean("continuous")).join()
            settings.set(Keys.voiceEnabled, baseline.getBoolean("voice_enabled")).join()
            settings.set(Keys.spokenFollowUp, baseline.getBoolean("spoken_follow_up")).join()
            settings.set(Keys.achievements, baseline.getBoolean("achievements")).join()
            settings.set(Keys.location, baseline.getBoolean("location")).join()
        }
        // Retain synthetic artifacts as evidence. No remaining draft/settings reference them.
        Files.move(marker.toPath(), File(directory, "completed-state.json").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun restoreOriginalDraft(original: ByteArray?, restoration: InspectionDraftStore = store.writer()) {
        // Claim revokes the closed Activity's writer; the monitor waits for any write/commit already in progress.
        restoration.withOwnership {
            if (original == null) {
                restoration.preserveDamaged()
            } else {
                val pending = File(draftFile.path + ".fixture-restore")
                FileOutputStream(pending).use {
                    it.write(original)
                    it.fd.sync()
                }
                Files.move(pending.toPath(), draftFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                assertTrue(original.contentEquals(draftFile.readBytes()))
            }
        }
    }

    private fun assertDraft(model: FieldDemoViewModel, expected: InspectionDraft) {
        assertEquals(expected.photo, model.photo?.path)
        assertEquals(expected.note, model.noteDraft)
        assertEquals(expected.plot, model.plotName)
        assertEquals(expected.row, model.rowName)
        assertEquals(expected.plant, model.plantName)
        assertEquals(expected.review, model.review)
        assertEquals(expected.reportName, model.reportName)
        assertEquals(expected.result(), model.result)
    }

    private fun say(activity: FieldDemoActivity, text: String) {
        check(activity.currentState().settings["voice_enabled"] == true) { "Injected voice command requires voiceEnabled: $text" }
        check(!activity.currentState().busy) { "Injected command while application busy: $text" }
        activity.handleVoiceCommand(requireNotNull(VoiceCommands.parseForScreen(text, activity.currentState().screen.protocolName)))
    }

    private fun await(scenario: ActivityScenario<FieldDemoActivity>, condition: (FieldDemoActivity) -> Boolean) {
        var lastState = "Activity not sampled"
        waitFor(details = { lastState }) {
            var satisfied = false
            scenario.onActivity {
                val model = it.fieldModel
                val state = it.currentState()
                lastState = "screen=${state.screen}; busy=${model.busy}; appBusy=${state.busy}; hasDraft=${model.hasDraft}; " +
                    "damaged=${model.damagedDraft}; draftSaving=${model.draftSaving}; draftError=${model.draftError}; " +
                    "message=${model.message}; voiceEnabled=${state.settings["voice_enabled"]}; report=${model.reportName}"
                satisfied = condition(it) && !state.busy
            }
            satisfied
        }
    }

    private fun waitFor(details: () -> String = { "snapshot condition" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for draft operation: ${details()}" }
            SystemClock.sleep(25)
        }
    }
}
