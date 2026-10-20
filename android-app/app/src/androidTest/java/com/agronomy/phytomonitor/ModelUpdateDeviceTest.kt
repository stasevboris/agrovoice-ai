package com.agronomy.phytomonitor

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.ml.PhotoFiles
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.updates.ModelUpdates
import com.agronomy.phytomonitor.updates.UpdateRelease
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Run each method separately, with a real host-side force-stop between phases.
 * Only synthetic zero-output fixture graphs are used; this is not diagnostic validation.
 */
class ModelUpdateDeviceTest {
    @get:org.junit.Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    private val activity get() = compose.activity
    private val folder get() = File(activity.getExternalFilesDir(null), "test-reports/model-update").apply { mkdirs() }
    private val savedState get() = File(folder, "original.json")
    private val pointer get() = File(activity.filesDir, "updates/active-model.json")

    @Test
    fun rejectIncompatibleGraph() {
        prepare()
        val before = pointer.takeIf { it.exists() }?.readBytes()
        val runningSha = activity.fieldModel.currentModel().sha256
        loginAndCheck()
        assertNotNull(activity.syncModel.state.value.offeredModel)
        say("установи обновление модели")
        compose.waitUntil(60_000) {
            !activity.syncModel.state.value.busy && activity.syncModel.state.value.messageIsError
        }
        assertArrayEquals(before, pointer.takeIf { it.exists() }?.readBytes())
        assertFalse(activity.syncModel.state.value.pendingModelRestart)
        assertEquals(runningSha, activity.fieldModel.currentModel().sha256)
        checkOldReport()
        record("reject_incompatible_graph", JSONObject().put("pointer_unchanged", true)
            .put("message", activity.syncModel.state.value.message))
    }

    @Test
    fun installFirstVersion() {
        prepare()
        val oldModel = activity.fieldModel.currentModel()
        loginAndCheck()
        install("v1", spoken = true)
        assertEquals(oldModel.id, activity.fieldModel.currentModel().id)
        assertEquals(oldModel.sha256, activity.fieldModel.currentModel().sha256)
        FieldDetector(activity).use { detector ->
            detector.initialize()
            assertEquals(oldModel.sha256, detector.models.single { it.id == detector.defaultModelId }.sha256)
        }
        checkOldReport()
        record("install_v1", JSONObject().put("process_keeps_old_model", true))
    }

    @Test
    fun verifyFirstAndInstallSecondVersion() {
        prepare()
        val first = verifyFixture("v1")
        loginAndCheck()
        install("v2", spoken = false)
        assertEquals(first.sha256, activity.fieldModel.currentModel().sha256)
        FieldDetector(activity).use { detector ->
            detector.initialize()
            assertEquals(first.sha256, detector.models.single { it.id == detector.defaultModelId }.sha256)
        }
        checkOldReport()
        record("install_v2", JSONObject().put("process_keeps_v1", true))
    }

    @Test
    fun verifySecondAndCorruptOwnBlob() {
        prepare()
        val second = verifyFixture("v2")
        corruptOwnFixture(second, "v2")
        checkOldReport()
        record("corrupt_v2", JSONObject().put("only_fixture_blob_changed", true))
    }

    @Test
    fun verifyFallbackAndCorruptFirstBlob() {
        prepare()
        val first = verifyFixture("v1")
        press(VoiceAction.CROP, "orange")
        compose.waitUntil(10_000) {
            activity.fieldModel.crop == "orange" && SettingsStore.get(activity).state.value?.crop == "orange"
        }
        corruptOwnFixture(first, "v1")
        checkOldReport()
        record("fallback_v1_and_corrupt", JSONObject().put("previous_valid_version_loaded", true)
            .put("selected_crop", "orange"))
    }

    @Test
    fun verifyBundledAndRestoreState() {
        prepare()
        try {
            assertNull(ModelUpdates.get(activity).load().release)
            assertNull(activity.syncModel.state.value.activeModel)
            assertFalse(activity.syncModel.state.value.pendingModelRestart)
            assertNotEquals(FIXTURE_MODEL, activity.fieldModel.currentModel().id)
            val bundled = JSONObject(activity.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
            val models = bundled.getJSONArray("models")
            val active = activity.fieldModel.currentModel()
            val entry = (0 until models.length()).map { models.getJSONObject(it) }.single { it.getString("id") == active.id }
            assertEquals(entry.getString("sha256"), active.sha256)
            assertEquals("orange", activity.fieldModel.crop)
            assertEquals("orange", SettingsStore.get(activity).state.value?.crop)
            assertFalse(active.crops.contains("orange"))
            FieldDetector(activity).use { detector ->
                detector.initialize()
                val model = detector.models.single { it.id == active.id }
                val bitmap = Bitmap.createBitmap(416, 416, Bitmap.Config.ARGB_8888)
                try {
                    val error = assertThrows(IllegalArgumentException::class.java) {
                        detector.detect(bitmap, model, "orange", .25f)
                    }
                    assertTrue(error.message.orEmpty().contains("не поддерживает"))
                    assertTrue(error.message.orEmpty().contains("Апельсин", ignoreCase = true))
                } finally {
                    bitmap.recycle()
                }
            }
            checkOldReport()
            record("bundled_fallback", JSONObject().put("model", active.id).put("sha256", active.sha256)
                .put("selected_crop_preserved", "orange").put("unsupported_crop_rejected", true))
        } finally {
            restore()
        }
    }

    /** Recovery after any failed phase; does not remove update blobs, books, photos or journal entries. */
    @Test
    fun restoreOriginalState() {
        checkPackage()
        restore()
    }

    private fun checkPackage() {
        check(activity.packageName == "com.agronomy.phytomonitor.verification") { "Isolated verification package only" }
    }

    private fun prepare() {
        checkPackage()
        val settings = SettingsStore.get(activity)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        compose.waitUntil(60_000) { !activity.currentState().busy }
        if (!savedState.exists()) {
            val oldModel = activity.fieldModel.currentModel()
            check(oldModel.id != FIXTURE_MODEL) { "Restore the previous test before starting another" }
            val saved = JSONObject().put("voice_enabled", previous.voiceEnabled).put("continuous", previous.continuous)
                .put("crop", previous.crop)
                .put("pointer_existed", pointer.exists())
                .put("pointer_base64", pointer.takeIf { it.exists() }?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } ?: "")
                .put("old_model", oldModel.id).put("old_sha256", oldModel.sha256)
            // A completed old report must keep its own catalog even after later model updates.
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                val photo = File(folder, "synthetic-original.png")
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val report = PhotoFiles.saveResult(activity, photo, bitmap,
                    DetectionResult(oldModel, emptyList(), 0.0, 0.0), activity.fieldModel.detector, "all", .25f)
                saved.put("old_report", report.absolutePath).put("old_report_sha256", FieldDetector.sha256(report))
            } finally {
                bitmap.recycle()
            }
            savedState.writeText(saved.toString(2))
        }
        runBlocking {
            settings.set(Keys.continuous, false).join()
            settings.set(Keys.voiceEnabled, true).join()
        }
    }

    private fun loginAndCheck() {
        val args = InstrumentationRegistry.getArguments()
        press(VoiceAction.SYNC_SETTINGS)
        compose.waitUntil(10_000) { activity.currentState().screen == Screen.SETTINGS }
        compose.runOnUiThread {
            activity.syncModel.login(requireNotNull(args.getString("syncOrigin")),
                requireNotNull(args.getString("syncUsername")), requireNotNull(args.getString("syncPassword")))
        }
        compose.waitUntil(30_000) {
            activity.syncModel.state.value.generation != null && !activity.syncModel.state.value.busy
        }
        say("проверь обновления")
        compose.waitUntil(30_000) {
            !activity.syncModel.state.value.busy && activity.syncModel.state.value.offeredModel != null
        }
    }

    private fun install(version: String, spoken: Boolean) {
        val release = requireNotNull(activity.syncModel.state.value.offeredModel)
        assertEquals("compatibility_fixture_$version", release.id)
        assertEquals(FIXTURE_MODEL, release.metadata.getJSONObject("manifest_entry").getString("id"))
        val state = JSONObject(savedState.readText()).put(version, release.toJson())
        savedState.writeText(state.toString(2))
        if (spoken) say("установи обновление модели") else press(VoiceAction.MODEL_UPDATE_INSTALL)
        compose.waitUntil(60_000) {
            !activity.syncModel.state.value.busy && activity.syncModel.state.value.pendingModelRestart
        }
        assertFalse(activity.syncModel.state.value.messageIsError)
        assertNull(activity.syncModel.state.value.offeredModel)
        assertEquals(release.sha256, ModelUpdates.get(activity).store.available("model").first().sha256)
    }

    private fun verifyFixture(version: String): UpdateRelease {
        val release = UpdateRelease.parse(JSONObject(savedState.readText()).getJSONObject(version))
        assertEquals(release.sha256, ModelUpdates.get(activity).load().release?.sha256)
        compose.waitUntil(30_000) { activity.syncModel.state.value.activeModel?.sha256 == release.sha256 }
        assertFalse(activity.syncModel.state.value.pendingModelRestart)
        assertEquals(FIXTURE_MODEL, activity.fieldModel.currentModel().id)
        assertEquals(release.sha256, activity.fieldModel.currentModel().sha256)
        FieldDetector(activity).use { detector ->
            detector.initialize()
            val model = detector.models.single { it.id == detector.defaultModelId }
            assertEquals(FIXTURE_MODEL, model.id)
            assertEquals(release.sha256, model.sha256)
            assertEquals(49, model.classes.size)
            assertEquals("taxonomy_v1", model.taxonomyVersion)
            val bitmap = Bitmap.createBitmap(416, 416, Bitmap.Config.ARGB_8888)
            try {
                val result = detector.detect(bitmap, model, "all", .25f)
                assertTrue("Constant fixture must have no detections", result.boxes.isEmpty())
                assertEquals(release.sha256, result.model.sha256)
                val photo = File(folder, "synthetic-$version.png")
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val report = PhotoFiles.saveResult(activity, photo, bitmap, result, detector, "all", .25f)
                val json = JSONObject(report.readText())
                assertEquals(FIXTURE_MODEL, json.getString("model"))
                assertEquals(release.sha256, json.getString("model_sha256"))
                val provenance = json.getJSONObject("result_provenance")
                assertEquals(49, provenance.getJSONArray("classes").length())
                assertEquals("taxonomy_v1", provenance.getString("taxonomy_version"))
                record("verified_$version", JSONObject().put("model", model.id).put("sha256", model.sha256)
                    .put("classes", model.classes.size).put("detections", result.boxes.size)
                    .put("saved_report_sha256", FieldDetector.sha256(report)))
            } finally {
                bitmap.recycle()
            }
        }
        return release
    }

    private fun corruptOwnFixture(release: UpdateRelease, version: String) {
        assertEquals("compatibility_fixture_$version", release.id)
        assertEquals(FIXTURE_MODEL, release.metadata.getJSONObject("manifest_entry").getString("id"))
        assertNotEquals(JSONObject(savedState.readText()).getString("old_sha256"), release.sha256)
        val store = ModelUpdates.get(activity).store
        assertTrue(store.valid(release))
        store.file(release).appendBytes(byteArrayOf(0))
        assertFalse(store.valid(release))
    }

    private fun checkOldReport() {
        val saved = JSONObject(savedState.readText())
        val report = File(saved.getString("old_report"))
        assertEquals(saved.getString("old_report_sha256"), FieldDetector.sha256(report))
        val json = JSONObject(report.readText())
        assertEquals(saved.getString("old_model"), json.getString("model"))
        assertEquals(saved.getString("old_sha256"), json.getString("model_sha256"))
    }

    private fun restore() {
        val saved = JSONObject(savedState.readText())
        if (saved.getBoolean("pointer_existed")) {
            pointer.parentFile!!.mkdirs()
            pointer.writeBytes(Base64.decode(saved.getString("pointer_base64"), Base64.NO_WRAP))
            assertArrayEquals(Base64.decode(saved.getString("pointer_base64"), Base64.NO_WRAP), pointer.readBytes())
        } else if (pointer.exists()) {
            check(pointer.delete()) { "Could not remove this test's active-model pointer" }
        }
        assertEquals(saved.getBoolean("pointer_existed"), pointer.exists())
        val settings = SettingsStore.get(activity)
        runBlocking {
            settings.set(Keys.continuous, saved.getBoolean("continuous")).join()
            settings.set(Keys.voiceEnabled, saved.getBoolean("voice_enabled")).join()
            settings.set(Keys.crop, saved.getString("crop")).join()
        }
        compose.waitUntil(10_000) {
            settings.state.value?.continuous == saved.getBoolean("continuous") &&
                settings.state.value?.voiceEnabled == saved.getBoolean("voice_enabled") &&
                settings.state.value?.crop == saved.getString("crop")
        }
        assertNull(settings.error.value)
        checkOldReport()
        record("restored", JSONObject().put("original_pointer_restored", true).put("settings_restored", true))
    }

    private fun record(phase: String, details: JSONObject) {
        val file = File(folder, "report.json")
        val report = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            .put("scope", "Isolated package; zero-output compatibility fixtures, not trained models; injected voice commands")
            .put("events", JSONArray())
        report.getJSONArray("events").put(details.put("phase", phase).put("status", "passed"))
        file.writeText(report.toString(2))
    }

    private fun press(action: VoiceAction, value: String = "") {
        compose.runOnUiThread { activity.actions.dispatch(ActionRequest(action, value, ActionSource.BUTTON)) }
        compose.waitForIdle()
    }

    private fun say(text: String) {
        compose.runOnUiThread {
            val command = requireNotNull(VoiceCommands.parseForScreen(text, activity.currentState().screen.protocolName))
            activity.handleVoiceCommand(command)
        }
        compose.waitForIdle()
    }

    companion object {
        private const val FIXTURE_MODEL = "verification_fixture"
    }
}
