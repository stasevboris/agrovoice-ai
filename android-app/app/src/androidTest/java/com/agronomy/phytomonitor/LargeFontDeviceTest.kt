package com.agronomy.phytomonitor

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.TextLayoutResult
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.voice.VoiceAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Lists texts that are cut on the main screens at the current system font scale: ellipsis, or characters
 * after the last visible line. Clipping inside a fixed-size box is not detected.
 * Run once with `settings put system font_scale 1.3/2.0`; restore the setting afterwards. Not a usability study.
 */
class LargeFontDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test fun reportCutTextsOnMainScreens() {
        val activity = compose.activity
        check(activity.packageName.endsWith(".verification")) { "Isolated verification package only" }
        compose.waitUntil(60_000) { !activity.currentState().busy }
        val screens = JSONArray()
        for ((action, screen) in listOf(null to Screen.MAIN, VoiceAction.JOURNAL to Screen.JOURNAL,
                VoiceAction.SETTINGS to Screen.SETTINGS, VoiceAction.KNOWLEDGE to Screen.KNOWLEDGE)) {
            if (action != null) {
                compose.runOnUiThread { activity.actions.dispatch(ActionRequest(VoiceAction.BACK, source = ActionSource.BUTTON)) }
                compose.waitForIdle()
                compose.runOnUiThread { activity.actions.dispatch(ActionRequest(action, source = ActionSource.BUTTON)) }
                compose.waitUntil(10_000) { activity.currentState().screen == screen }
            }
            compose.waitForIdle()
            val cut = JSONArray()
            val root = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().first()
            visit(root) { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.firstOrNull() ?: return@visit
                val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
                // Characters after the last visible line were dropped (maxLines without ellipsis).
                val hidden = layout.layoutInput.text.length - layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
                if (ellipsized || hidden > 0) {
                    cut.put(JSONObject().put("text", layout.layoutInput.text.text.take(80))
                        .put("ellipsized", ellipsized).put("hidden_chars", hidden))
                }
            }
            screens.put(JSONObject().put("screen", screen.name).put("cut", cut))
        }
        val report = JSONObject().put("font_scale", activity.resources.configuration.fontScale.toDouble())
            .put("density_dpi", activity.resources.configuration.densityDpi).put("screens", screens)
            .put("scope", "Visible part of each screen without scrolling; programmatic, not a TalkBack or user study")
        File(activity.getExternalFilesDir(null), "test-reports").apply { mkdirs() }
            .resolve("large-font-${activity.resources.configuration.fontScale}.json").writeText(report.toString(2))
    }

    private fun visit(node: SemanticsNode, block: (SemanticsNode) -> Unit) {
        block(node)
        node.children.forEach { visit(it, block) }
    }
}
