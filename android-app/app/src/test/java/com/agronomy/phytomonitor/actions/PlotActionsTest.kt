package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.FreeSpeech
import com.agronomy.phytomonitor.voice.Understanding
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class PlotActionsTest {
    private val north = PlotTarget("stable-id", "Северный")
    private val state = AppState(screen = Screen.PLOTS, plotTargets = listOf(north), plots = listOf(north.name))
    private fun request(action: VoiceAction, value: String = "") = ActionRequest(action, value, ActionSource.VOICE)

    @Test
    fun createAndRenameShareButtonAndVoiceChecks() {
        val create = request(VoiceAction.CREATE_PLOT, "  Южный   склон ")
        val expected = Verdict.Run(listOf(Operation.CreatePlot("Южный склон")))
        assertEquals(expected, ActionPolicy.resolve(create, state))
        assertEquals(expected, ActionPolicy.resolve(create.copy(source = ActionSource.BUTTON), state))
        assertTrue(ActionPolicy.resolve(create.copy(value = "СЕВЕРНЫЙ"), state) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(create, state.copy(screen = Screen.MAIN)) is Verdict.Clarify)
        val rename = request(VoiceAction.RENAME_PLOT, PlotActions.renameValue("Северный", "Восточный"))
        assertEquals(Verdict.Run(listOf(Operation.RenamePlot(north.id, "Восточный"))), ActionPolicy.resolve(rename, state))
        assertTrue(ActionPolicy.resolve(rename.copy(value = "broken"), state) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(create.copy(value = "а".repeat(121)), state) is Verdict.Clarify)
    }

    @Test
    fun deletionOnlyAsksAndCancelOrChangedTargetDoesNotDelete() {
        val delete = request(VoiceAction.DELETE_PLOT, "северный")
        assertEquals(Verdict.Run(listOf(Operation.AskDeletePlot(north))), ActionPolicy.resolve(delete, state))
        val pending = state.copy(pendingPlotDeletion = north)
        assertEquals(Verdict.Run(listOf(Operation.CancelPlotDeletion)), ActionPolicy.resolve(request(VoiceAction.CANCEL), pending))
        assertEquals(Verdict.Run(listOf(Operation.DeletePlot(north))), ActionPolicy.resolve(request(VoiceAction.CONFIRM), pending))
        val changed = ActionPolicy.resolve(request(VoiceAction.CONFIRM), pending.copy(plotTargets = listOf(north.copy(name = "Другой")))) as Verdict.Run
        assertEquals(listOf(Operation.CancelPlotDeletion), changed.operations)
        assertTrue(changed.noticeIsError)
        assertTrue(ActionPolicy.resolve(request(VoiceAction.CONFIRM), state) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(request(VoiceAction.CREATE_PLOT, "Новый"), pending) is Verdict.Clarify)
    }

    @Test
    fun deletionNeedsANewUtteranceAfterTheQuestionEvenInAssistantPlans() {
        val operations = mutableListOf<Operation>()
        var current = state
        val dispatcher = ActionDispatcher(object : ActionPort {
            override fun state() = current
            override fun perform(operation: Operation) {
                operations += operation
                if (operation is Operation.AskDeletePlot) current = current.copy(pendingPlotDeletion = operation.target)
            }
            override fun tell(text: String, isError: Boolean, request: ActionRequest?) = Unit
        })
        dispatcher.submitPlan(listOf(request(VoiceAction.DELETE_PLOT, "Северный"), request(VoiceAction.CONFIRM)))
        assertEquals(listOf(Operation.AskDeletePlot(north)), operations)
        dispatcher.dispatch(request(VoiceAction.CONFIRM))
        assertEquals(Operation.DeletePlot(north), operations.last())
    }

    @Test
    fun russianNamesNegationAndAmbiguityAreHandledBeforeMutation() {
        val create = VoiceCommands.parseForScreen("создай участок южный склон", "plots")!!
        assertEquals(VoiceAction.CREATE_PLOT, create.action)
        assertEquals("южный склон", create.value)
        val rename = VoiceCommands.parseForScreen("переименуй участок северный в южный", "plots", state.plots)!!
        assertEquals(PlotActions.renameValue("Северный", "южный"), rename.value)
        assertTrue(FreeSpeech.understand("удали участок северный", "plots", state.plots) is Understanding.Commands)
        assertTrue(FreeSpeech.understand("не удали участок северный", "plots", state.plots) !is Understanding.Commands)
        assertNull(VoiceCommands.parseForScreen("создай участок южный", "main"))
        val ambiguous = state.copy(plotTargets = listOf(PlotTarget("1", "Ёлки"), PlotTarget("2", "Елки")))
        assertTrue(ActionPolicy.resolve(request(VoiceAction.DELETE_PLOT, "елки"), ambiguous) is Verdict.Clarify)
    }
}
