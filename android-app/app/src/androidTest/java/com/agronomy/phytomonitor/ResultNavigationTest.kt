package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ResultNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test
    fun resultCanBeReopenedWithoutAnalysisAndJournalReturnsToIt() {
        compose.waitUntil(60000) { !compose.activity.fieldModel.busy }
        assertNotNull("A device photo fixture is required", compose.activity.fieldModel.bitmap)
        assertFalse(compose.activity.currentState().resultOpen)
        compose.runOnUiThread { compose.activity.fieldModel.analyze() }
        compose.waitUntil(60000) { !compose.activity.fieldModel.busy && compose.activity.currentState().resultOpen }
        val report = compose.activity.fieldModel.reportName
        val result = compose.activity.fieldModel.result
        compose.onNodeWithText("К снимку").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        try {
            compose.waitUntil(10000) { !compose.activity.currentState().resultOpen }
        } catch (error: Throwable) {
            val activity = compose.activity
            val state = activity.currentState()
            throw AssertionError("Back: screen=${state.screen}, result=${state.resultOpen}, followUp=${state.followUp}, help=${state.helpOpen}, finishing=${activity.isFinishing}, destroyed=${activity.isDestroyed}", error)
        }
        compose.onNodeWithText("Открыть результат").performClick()
        assertTrue(compose.activity.currentState().resultOpen)
        assertSame(result, compose.activity.fieldModel.result)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.activity.currentState().resultOpen }
        assertEquals(report, compose.activity.fieldModel.reportName)
        compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("открой журнал")!!) }
        assertEquals(Screen.JOURNAL, compose.activity.currentState().screen)
        compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("назад")!!) }
        assertTrue(compose.activity.currentState().resultOpen)
        assertEquals(report, compose.activity.fieldModel.reportName)
    }
}
