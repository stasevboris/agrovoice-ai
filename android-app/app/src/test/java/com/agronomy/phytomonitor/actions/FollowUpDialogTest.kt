package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-02: every transition of the spoken question after a hands-free photo. */
class FollowUpDialogTest {
    private var now = 0L
    private val dialog = FollowUpDialog { now }
    private val withResult = AppState(hasPhoto = true, hasResult = true, plots = listOf("Теплица 2"))

    private fun say(text: String, state: AppState): Pair<ActionRequest, Verdict> {
        val command = VoiceCommands.parseForScreen(text, "main", state.plots)!!
        val request = ActionRequest(command.action, command.value, ActionSource.VOICE)
        return request to ActionPolicy.resolve(request, state.copy(followUp = dialog.awaitingAnswer))
    }

    private fun asked() {
        dialog.voiceAnalysisRequested()
        assertEquals(FollowUpDialog.Prompt.RESULT_AND_QUESTION, dialog.analysisFinished(hasResult = true))
    }

    @Test
    fun voicePhotoLeadsToTheQuestionAndYesSaves() {
        asked()
        assertEquals(FollowUpDialog.Phase.FOLLOW_UP, dialog.phase)
        val (request, verdict) = say("да", withResult)
        assertEquals(listOf(Operation.SaveInspection), (verdict as Verdict.Run).operations)
        assertNull(dialog.commandHandled(request, verdict))
        assertEquals(FollowUpDialog.Phase.SAVING, dialog.phase)
        assertEquals(FollowUpDialog.Prompt.SAVED, dialog.saveFinished(failed = false))
        assertEquals(FollowUpDialog.Phase.IDLE, dialog.phase)
    }

    @Test
    fun failedSaveIsReported() {
        asked()
        val (request, verdict) = say("да", withResult)
        dialog.commandHandled(request, verdict)
        assertEquals(FollowUpDialog.Prompt.SAVE_FAILED, dialog.saveFinished(failed = true))
    }

    @Test
    fun noEndsWithoutSaving() {
        asked()
        val (request, verdict) = say("нет", withResult)
        assertEquals(listOf(Operation.EndFollowUp), (verdict as Verdict.Run).operations)
        assertEquals(FollowUpDialog.Prompt.NOT_SAVED, dialog.commandHandled(request, verdict))
        assertEquals(FollowUpDialog.Phase.IDLE, dialog.phase)
    }

    @Test
    fun rowAndPlotKeepTheQuestionOpen() {
        asked()
        val (row, rowVerdict) = say("ряд пять", withResult)
        assertNull(dialog.commandHandled(row, rowVerdict))
        val (plot, plotVerdict) = say("участок теплица два", withResult)
        assertNull(dialog.commandHandled(plot, plotVerdict))
        assertTrue(dialog.awaitingAnswer)
    }

    @Test
    fun alreadySavedYesConfirmsWithoutSavingTwice() {
        asked()
        val (request, verdict) = say("да", withResult.copy(inspectionSaved = true))
        assertTrue(verdict is Verdict.Skip)
        assertEquals(FollowUpDialog.Prompt.SAVED, dialog.commandHandled(request, verdict))
    }

    @Test
    fun anotherCommandOrStopLeavesTheQuestion() {
        asked()
        val (request, verdict) = say("открой журнал", withResult)
        assertNull(dialog.commandHandled(request, verdict))
        assertEquals(FollowUpDialog.Phase.IDLE, dialog.phase)
        asked()
        dialog.stop()
        assertFalse(dialog.awaitingAnswer)
    }

    @Test
    fun answerWindowStartsWhenTheMicrophoneOpens() {
        asked()
        now += 60_000
        assertTrue("reading the result must not use up the answer time", dialog.awaitingAnswer)
        dialog.listeningStarted()
        now += FollowUpDialog.FOLLOW_UP_MS - 1
        assertTrue(dialog.shouldListenAgain())
        dialog.listeningStarted()
        now += 2
        assertFalse(dialog.shouldListenAgain())
        assertEquals(FollowUpDialog.Phase.IDLE, dialog.phase)
    }

    @Test
    fun noQuestionWithoutAVoiceRequestOrWithoutAResult() {
        assertNull(dialog.analysisFinished(hasResult = true))
        dialog.voiceAnalysisRequested()
        assertNull(dialog.analysisFinished(hasResult = false))
        assertEquals(FollowUpDialog.Phase.IDLE, dialog.phase)
    }

    @Test
    fun yesWithoutAQuestionIsNotACommand() {
        val request = ActionRequest(VoiceAction.CONFIRM, source = ActionSource.VOICE)
        assertEquals(Verdict.Clarify(ActionPolicy.WHICH_ACTION), ActionPolicy.resolve(request, withResult))
        assertEquals(Verdict.Clarify(ActionPolicy.NOTHING_TO_SAVE), ActionPolicy.resolve(request, AppState(followUp = true)))
        assertEquals("save", AppState(followUp = true).pending)
        assertTrue("да" in VoiceCommands.mainGrammar(emptyList(), followUp = true))
        assertFalse("да" in VoiceCommands.mainGrammar(emptyList()))
    }
}
