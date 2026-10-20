package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.*
import org.junit.Test

class FieldActionPolicyTest {
    private val field = AppState(experimentalControls = false, hasPhoto = true,
        availableModels = listOf("current", "other"), selectedModel = "current")

    @Test fun fieldRejectsExperimentsForButtonsVoiceAndAssistant() {
        for (source in ActionSource.entries) {
            for ((action, value) in listOf(VoiceAction.MODEL to "other", VoiceAction.THRESHOLD to "40",
                VoiceAction.THRESHOLD_UP to "", VoiceAction.THRESHOLD_DOWN to "", VoiceAction.COMPARE to "")) {
                assertTrue("$source $action", ActionPolicy.resolve(ActionRequest(action, value, source), field) is Verdict.Reject)
            }
        }
    }

    @Test fun fieldStillAnalyzesAndLabCanAdjustThreshold() {
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.ANALYZE, source = ActionSource.BUTTON), field) is Verdict.Run)
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.THRESHOLD, "40", ActionSource.VOICE),
            field.copy(experimentalControls = true)) is Verdict.Run)
    }
}
