package com.agronomy.phytomonitor.updates

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.ml.ModelCatalog
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val taxonomy = JSONObject().put("classes", JSONArray()
        .put(JSONObject().put("id", 0).put("code", "apple__healthy").put("crop", "apple").put("ru", "Яблоня: здоровый лист")))
        .put("crops_ru", JSONObject().put("apple", "Яблоня"))
    private val bundled = JSONObject().put("classes", JSONArray(listOf("Apple leaf")))
        .put("labels_ru", JSONArray(listOf("Яблоня: лист")))
        .put("models", JSONArray().put(JSONObject().put("id", "bundled").put("name", "Bundled")
            .put("asset", "models/bundled.onnx").put("sha256", "b".repeat(64)).put("family", "yolo")))

    private fun release(text: String, version: String): UpdateRelease {
        val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val entry = JSONObject().put("id", "allcrops").put("name", "Allcrops")
            .put("asset", "models/allcrops.onnx").put("sha256", sha).put("family", "yolo")
            .put("dataset", "allcrops_v1").put("side", 416).put("extra_provenance", "retained")
            .put("classes", JSONArray(listOf("apple__healthy"))).put("labels_ru", JSONArray(listOf("Яблоня: здоровый лист")))
            .put("crops", JSONArray(listOf("apple"))).put("crops_ru", JSONObject().put("apple", "Яблоня"))
        return UpdateRelease("release-$version", "model", version, "model.onnx", text.toByteArray().size.toLong(), sha,
            JSONObject().put("prepared_by", "prepare_model_release.py").put("manifest_entry", entry))
    }

    private fun staged(store: VerifiedUpdateStore, text: String, version: String): UpdateRelease =
        release(text, version).also { store.stage(it, ByteArrayInputStream(text.toByteArray())) }

    private fun manager(store: VerifiedUpdateStore) = ModelUpdates(store, bundled, taxonomy) { file, _ ->
        require(file.readText() != "unloadable") { "ORT load failed" }
    }

    @Test fun installRequiresRestartAndPreservesCompleteMetadata() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val process = manager(store)
        val next = staged(store, "working", "next")
        assertNull(process.load().release)
        process.install(next)
        assertNull(process.load().release)
        val restarted = manager(store).load()
        assertEquals(next.sha256, restarted.model!!.sha256)
        assertEquals("allcrops", restarted.model.id)
        val entry = restarted.manifest.getJSONArray("models").getJSONObject(1)
        assertEquals("retained", entry.getString("extra_provenance"))
        assertEquals("release-next", entry.getJSONObject("update_release").getString("id"))
        assertEquals("taxonomy_v1", ModelCatalog.parse(restarted.manifest).single { it.id == "allcrops" }.taxonomyVersion)
    }

    @Test fun corruptOrUnloadableCurrentFallsBackToPreviousThenBundled() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val good = staged(store, "working", "good")
        store.activate(good)
        val bad = staged(store, "unloadable", "bad")
        store.activate(bad)
        val previous = manager(store).load()
        assertEquals(good.sha256, previous.model!!.sha256)
        assertNotNull(previous.notice)
        store.file(bad).writeText("damaged")
        assertEquals(good.sha256, manager(store).load().model!!.sha256)
        store.file(good).writeText("damaged")
        val fallback = manager(store).load()
        assertNull(fallback.release)
        assertNull(fallback.model)
        assertNotNull(fallback.notice)
        assertEquals("bundled", fallback.manifest.getJSONArray("models").getJSONObject(0).getString("id"))
    }

    @Test fun metadataCannotRenameCanonicalLabelsOrCrops() {
        val process = manager(VerifiedUpdateStore(temporary.newFolder()))
        for (key in listOf("labels_ru", "crops", "crops_ru")) {
            val item = release("working", key)
            item.metadata.getJSONObject("manifest_entry").put(key,
                if (key == "crops_ru") JSONObject().put("apple", "Другая культура") else JSONArray(listOf("wrong")))
            assertThrows(Exception::class.java) { process.compatible(item) }
            assertNull(process.offered(listOf(item)))
        }
    }

    @Test fun failedValidationDoesNotReplaceWorkingPointer() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val old = staged(store, "working", "old")
        store.activate(old)
        val process = manager(store)
        val bad = staged(store, "unloadable", "bad")
        assertThrows(IllegalArgumentException::class.java) { process.install(bad) }
        assertEquals(old.sha256, store.available("model").first().sha256)
    }

    @Test fun runtimeFallbackDoesNotActivateAnUpdateInstalledInThisProcess() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val old = staged(store, "old weights", "old")
        store.activate(old)
        val process = manager(store)
        assertEquals(old.sha256, process.load().release!!.sha256)
        assertEquals(old.sha256, process.state.value!!.release!!.sha256)
        val next = staged(store, "new weights", "next")
        process.install(next)
        assertEquals(next.sha256, process.pending!!.sha256)
        assertEquals(old.sha256, process.state.value!!.release!!.sha256)
        assertNull(process.reject(old.sha256).release)
        assertNull(process.state.value!!.release)
        assertNotNull(process.state.value!!.notice)
        assertEquals(next.sha256, manager(store).load().release!!.sha256)
    }

    @Test fun reinstallingProcessActiveVersionRestoresTheRequestedPointer() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val old = staged(store, "old weights", "old")
        store.activate(old)
        val process = manager(store)
        val next = staged(store, "new weights", "next")
        process.install(next)
        assertEquals(next.sha256, store.available("model").first().sha256)
        process.install(old)
        assertEquals(old.sha256, store.available("model").first().sha256)
        assertNull(process.pending)
        assertEquals(old.sha256, manager(store).load().release!!.sha256)
    }

    @Test fun cancellationDuringStartupValidationIsNotSilentlyReplacedByBundled() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        store.activate(staged(store, "weights", "next"))
        val process = ModelUpdates(store, bundled, taxonomy) { _, _ ->
            throw java.util.concurrent.CancellationException("Cancelled")
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) { process.load() }
        assertNull(process.state.value)
    }

    @Test fun cancelledInstallDoesNotChangePointerOrPendingState() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val old = staged(store, "old weights", "old")
        store.activate(old)
        val process = manager(store)
        val next = staged(store, "new weights", "next")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            process.install(next, active = { false })
        }
        assertEquals(old.sha256, store.available("model").first().sha256)
        assertNull(process.pending)
    }

    @Test fun graphSignatureRejectsWrongTypesAndLayouts() {
        val input = longArrayOf(1, 3, 416, 416)
        val output = longArrayOf(1, 5, 3549)
        assertTrue(ModelUpdates.supportedGraph(input, output, true, true, 416, 1))
        assertFalse(ModelUpdates.supportedGraph(input, output, false, true, 416, 1))
        assertFalse(ModelUpdates.supportedGraph(input, output, true, false, 416, 1))
        assertFalse(ModelUpdates.supportedGraph(longArrayOf(1, 3, 640, 640), output, true, true, 416, 1))
        assertFalse(ModelUpdates.supportedGraph(input, longArrayOf(1, 300, 6), true, true, 416, 1))
    }

    @Test fun buttonsAndVoiceUseTheSameInstallPolicy() {
        val signedIn = AppState(screen = Screen.SETTINGS, syncGeneration = "g")
        for (source in listOf(ActionSource.BUTTON, ActionSource.VOICE)) {
            val request = ActionRequest(VoiceAction.MODEL_UPDATE_INSTALL, source = source)
            assertTrue(ActionPolicy.resolve(request, signedIn) is Verdict.Clarify)
            assertEquals(Verdict.Run(listOf(Operation.InstallModelUpdate)),
                ActionPolicy.resolve(request, signedIn.copy(modelUpdate = "new")))
            assertTrue(ActionPolicy.resolve(request, AppState(modelUpdate = "new")) is Verdict.Clarify)
        }
        assertEquals(VoiceAction.MODEL_UPDATE_INSTALL,
            VoiceCommands.parseForScreen("установи обновление модели", "settings")!!.action)
    }
}
