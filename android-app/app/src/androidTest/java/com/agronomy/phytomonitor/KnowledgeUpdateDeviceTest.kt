package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.sync.SyncSessionStore
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Isolated app + local verify API over adb reverse: feed → download → SHA/format → activation → open catalog.
 * Run with -e syncOrigin/-e syncUsername/-e syncPassword; never against the production package.
 */
class KnowledgeUpdateDeviceTest {
    @get:org.junit.Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test fun checkAndInstallReferenceBookFromLocalServer() {
        val activity = compose.activity
        check(activity.packageName == "com.agronomy.phytomonitor.verification") { "Isolated verification package only" }
        val args = InstrumentationRegistry.getArguments()
        val settings = SettingsStore.get(activity)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        val updates = File(activity.filesDir, "updates")
        val started = System.currentTimeMillis()
        runBlocking { settings.set(Keys.voiceEnabled, true).join() }
        try {
            compose.waitUntil(60_000) { !activity.currentState().busy }
            assertNull(activity.knowledgeModel.state.value.updateVersion)
            press(VoiceAction.SYNC_SETTINGS)
            compose.waitUntil(10_000) { activity.currentState().screen == Screen.SETTINGS }
            compose.runOnUiThread {
                activity.syncModel.login(requireNotNull(args.getString("syncOrigin")),
                    requireNotNull(args.getString("syncUsername")), requireNotNull(args.getString("syncPassword")))
            }
            compose.waitUntil(30_000) { activity.syncModel.state.value.generation != null && !activity.syncModel.state.value.busy }
            // Install before checking must be refused by the shared policy, not by a hidden button.
            say("установи обновление справочника")
            assertEquals(0, activity.syncModel.state.value.knowledgeRevision)
            say("проверь обновления")
            compose.waitUntil(30_000) { !activity.syncModel.state.value.busy && activity.syncModel.state.value.updates.isNotEmpty() }
            val offered = requireNotNull(activity.syncModel.state.value.offeredKnowledge) { activity.syncModel.state.value.message.orEmpty() }
            // The spoken/shown notice is a temporary message over the bottom controls; tap after it is gone.
            compose.waitUntil(30_000) { activity.fieldModel.message == null }
            compose.onNodeWithText("Установить справочник ${offered.version}").performScrollTo().performClick()
            runCatching {
                compose.waitUntil(60_000) { activity.syncModel.state.value.knowledgeRevision == 1 && !activity.syncModel.state.value.busy }
            }.onFailure { throw AssertionError("Install did not finish: ${activity.syncModel.state.value.message}", it) }
            compose.waitUntil(10_000) { activity.knowledgeModel.state.value.updateVersion == offered.version }
            assertFalse(activity.syncModel.state.value.messageIsError)
            assertNull(activity.syncModel.state.value.offeredKnowledge)
            val catalog = requireNotNull(activity.knowledgeModel.state.value.catalog)
            assertTrue(catalog.cards.isNotEmpty())
            File(activity.getExternalFilesDir(null), "test-reports").apply { mkdirs() }.resolve("knowledge-update.json").writeText(
                JSONObject().put("status", "passed").put("release", offered.toJson()).put("cards", catalog.cards.size)
                    .put("statuses", catalog.cards.map { it.status }.distinct().sorted().joinToString())
                    .put("message", activity.syncModel.state.value.message)
                    .put("elapsed_ms", System.currentTimeMillis() - started)
                    .put("scope", "Isolated package, local verify API over USB reverse; voice commands injected as recognised text").toString(2))
        } finally {
            compose.runOnUiThread { activity.syncModel.logout() }
            compose.waitUntil(30_000) { SyncSessionStore.get(activity).session.value == null && !activity.syncModel.state.value.busy }
            runBlocking { settings.set(Keys.voiceEnabled, previous.voiceEnabled).join() }
            updates.listFiles()?.forEach { it.delete() }
        }
    }

    private fun press(action: VoiceAction) {
        compose.runOnUiThread { compose.activity.actions.dispatch(ActionRequest(action, source = ActionSource.BUTTON)) }
        compose.waitForIdle()
    }

    private fun say(text: String) {
        compose.runOnUiThread {
            val command = requireNotNull(VoiceCommands.parseForScreen(text, compose.activity.currentState().screen.protocolName)) { text }
            compose.activity.handleVoiceCommand(command)
        }
        compose.waitForIdle()
    }
}
