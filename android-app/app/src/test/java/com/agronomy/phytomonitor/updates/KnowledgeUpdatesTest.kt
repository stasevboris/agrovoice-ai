package com.agronomy.phytomonitor.updates

import com.agronomy.phytomonitor.actions.ActionPolicy
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AppState
import com.agronomy.phytomonitor.actions.Operation
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.actions.Verdict
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val bundled: String by lazy {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "kb/kb_v1.json").isFile }
        File(root, "kb/kb_v1.json").readText()
    }

    private fun release(text: String, kind: String = "kb", version: String = "next"): UpdateRelease {
        val bytes = text.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return UpdateRelease.parse(JSONObject().put("id", "$kind-$version").put("kind", kind).put("version", version)
            .put("file_name", "file.bin").put("bytes", bytes.size).put("sha256", sha)
            .put("download_path", "/updates/$kind-$version/download").put("metadata", JSONObject()))
    }

    private fun install(store: VerifiedUpdateStore, item: UpdateRelease, text: String) {
        store.stage(item, ByteArrayInputStream(text.toByteArray()))
        store.activate(item)
    }

    @Test fun verifiedUpdateReplacesBundledAndBrokenFormatFallsBack() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        assertNull(KnowledgeUpdates.load(store) { bundled }.release)
        val updated = JSONObject(bundled).put("version", "2026.10.20-test").toString()
        val good = release(updated, version = "good")
        install(store, good, updated)
        val loaded = KnowledgeUpdates.load(store) { bundled }
        assertEquals("good", loaded.release!!.version)
        // SHA-valid but not a reference book: the app must not show an empty or broken catalog.
        install(store, release("{}", version = "bad"), "{}")
        assertEquals("good", KnowledgeUpdates.load(store) { bundled }.release!!.version)
    }

    @Test fun installKeepsTheBookReallyInUseAsRollbackTarget() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val textA = JSONObject(bundled).put("kb_version", "working-A").toString()
        val a = release(textA, version = "working-A")
        install(store, a, textA)
        // SHA-valid B that does not parse: the loader keeps using A.
        install(store, release("{}", version = "bad-B"), "{}")
        assertEquals("working-A", KnowledgeUpdates.active(store)!!.version)
        val textC = JSONObject(bundled).put("kb_version", "working-C").toString()
        val c = release(textC, version = "working-C")
        store.stage(c, ByteArrayInputStream(textC.toByteArray()))
        KnowledgeUpdates.install(store, c)
        assertEquals("working-C", KnowledgeUpdates.active(store)!!.version)
        store.file(c).writeText("corrupted-after-install")
        assertEquals("working-A", KnowledgeUpdates.load(store) { bundled }.release!!.version)
    }

    @Test fun endedSessionCannotActivateAParsedBook() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val text = JSONObject(bundled).put("kb_version", "late").toString()
        val late = release(text, version = "late")
        store.stage(late, ByteArrayInputStream(text.toByteArray()))
        val result = runCatching {
            KnowledgeUpdates.install(store, late) { throw java.util.concurrent.CancellationException("Logged out") }
        }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertNull(KnowledgeUpdates.active(store))
        assertTrue(runCatching { KnowledgeUpdates.install(store, release("{}", version = "bad")) }.isFailure)
    }

    @Test fun offerSkipsActiveAndModelsAreNotInstalled() {
        val old = release("old", version = "old")
        val next = release("next", version = "next")
        val model = release("weights", kind = "model", version = "m1")
        assertEquals(next, KnowledgeUpdates.offered(listOf(old, next, model), old))
        assertNull(KnowledgeUpdates.offered(listOf(next, model), next))
        assertEquals("Новых версий справочника нет.", KnowledgeUpdates.describe(listOf(model), null))
    }

    @Test fun installNeedsCheckedOfferAndSessionForButtonsAndVoice() {
        val signedIn = AppState(screen = Screen.SETTINGS, syncGeneration = "g")
        for (source in listOf(ActionSource.BUTTON, ActionSource.VOICE)) {
            val install = ActionRequest(VoiceAction.UPDATES_INSTALL, source = source)
            assertTrue(ActionPolicy.resolve(install, signedIn) is Verdict.Clarify)
            assertEquals(Verdict.Run(listOf(Operation.InstallKnowledgeUpdate)),
                ActionPolicy.resolve(install, signedIn.copy(knowledgeUpdate = "next")))
            assertTrue(ActionPolicy.resolve(install, AppState(screen = Screen.SETTINGS, knowledgeUpdate = "next")) is Verdict.Clarify)
        }
        assertEquals(VoiceAction.UPDATES_CHECK, VoiceCommands.parseForScreen("проверь обновления", "settings")!!.action)
        assertEquals(VoiceAction.UPDATES_INSTALL, VoiceCommands.parseForScreen("установи обновление справочника", "settings")!!.action)
    }
}
