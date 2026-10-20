package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.FreeSpeech
import com.agronomy.phytomonitor.voice.Understanding
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class FeedbackActionsTest {
    @Test
    fun assistantCannotConfirmExportInSamePlanAsPrivacyWarning() {
        val operations = mutableListOf<Operation>()
        var state = AppState(screen = Screen.JOURNAL, inspectionOpen = true, openedInspectionId = "saved", frameCount = 2, feedbackExportCount = 1)
        val port = object : ActionPort {
            override fun state() = state
            override fun perform(operation: Operation) {
                operations += operation
                if (operation == Operation.PrepareFeedbackExport) state = state.copy(feedbackExportPending = true)
            }
            override fun tell(text: String, isError: Boolean, request: ActionRequest?) = Unit
        }
        val dispatcher = ActionDispatcher(port)
        dispatcher.submitPlan(listOf(ActionRequest(VoiceAction.EXPORT_FEEDBACK, source = ActionSource.ASSISTANT),
            ActionRequest(VoiceAction.CONFIRM, source = ActionSource.ASSISTANT)))
        assertEquals(listOf(Operation.PrepareFeedbackExport), operations)
        assertEquals(0, dispatcher.remainingSteps)
        dispatcher.dispatch(ActionRequest(VoiceAction.CONFIRM, source = ActionSource.BUTTON))
        assertEquals(listOf(Operation.PrepareFeedbackExport, Operation.LaunchFeedbackExport), operations)
    }

    private val saved = AppState(screen = Screen.JOURNAL, inspectionOpen = true, openedInspectionId = "saved", frameCount = 2, selectedFrame = 2, feedbackExportCount = 1)

    @Test
    fun buttonAndVoiceUseSameOperationAndBusyOrOtherScreensCannotEdit() {
        val command = VoiceCommands.parseForScreen("не знаю", "journal")!!
        val request = ActionRequest(command.action, command.value, ActionSource.VOICE)
        val expected = Verdict.Run(listOf(Operation.SaveFrameFeedback("unknown", "")))
        assertEquals(expected, ActionPolicy.resolve(request, saved))
        assertEquals(expected, ActionPolicy.resolve(request.copy(source = ActionSource.BUTTON), saved))
        assertTrue(ActionPolicy.resolve(request, saved.copy(busy = true)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(request, saved.copy(inspectionOpen = false)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(request, saved.copy(screen = Screen.MAIN)) is Verdict.Clarify)
    }

    @Test
    fun freeCorrectionRemainsUserTextAndExportOnlyPreparesDialog() {
        val understood = FreeSpeech.understand("исправление кадра другая культура", "journal") as Understanding.Commands
        val command = understood.commands.single()
        assertEquals(Verdict.Run(listOf(Operation.SaveFrameFeedback("incorrect", "другая культура"))),
            ActionPolicy.resolve(ActionRequest(command.action, command.value, ActionSource.VOICE), saved))
        val export = ActionPolicy.resolve(ActionRequest(VoiceAction.EXPORT_FEEDBACK, source = ActionSource.VOICE), saved) as Verdict.Run
        assertEquals(listOf(Operation.PrepareFeedbackExport), export.operations)
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.EXPORT_FEEDBACK, source = ActionSource.VOICE),
            saved.copy(feedbackExportCount = 0)) is Verdict.Clarify)
        assertTrue(export.notice!!.contains("личные"))
        assertEquals(Verdict.Run(listOf(Operation.LaunchFeedbackExport)), ActionPolicy.resolve(
            ActionRequest(VoiceAction.CONFIRM, source = ActionSource.VOICE), saved.copy(feedbackExportPending = true)))
        assertEquals(Verdict.Run(listOf(Operation.CancelFeedbackExport)), ActionPolicy.resolve(
            ActionRequest(VoiceAction.CANCEL, source = ActionSource.BUTTON), saved.copy(feedbackExportPending = true)))
        assertNull(VoiceCommands.parseForScreen("не экспортируй выбранные кадры", "journal"))
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.FRAME_FEEDBACK,
            FeedbackActions.value("correct", "чужая правка"), ActionSource.BUTTON), saved) is Verdict.Reject)
    }
}
