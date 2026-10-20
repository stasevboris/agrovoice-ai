package com.agronomy.phytomonitor.sync

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class SyncActionsTest {
    private val saved = AppState(screen = Screen.JOURNAL, inspectionOpen = true, openedInspectionId = "saved-1", syncGeneration = "session-a")

    @Test fun buttonAndVoiceUseSameCurrentInspectionOperation() {
        val button = ActionPolicy.resolve(ActionRequest(VoiceAction.SYNC_CURRENT, source = ActionSource.BUTTON), saved)
        val voice = ActionPolicy.resolve(ActionRequest(VoiceAction.SYNC_CURRENT, source = ActionSource.VOICE), saved)
        assertEquals(button, voice)
        assertEquals(listOf(Operation.SendInspection("saved-1")), (voice as Verdict.Run).operations)
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.SYNC_CURRENT, source = ActionSource.VOICE), saved.copy(syncGeneration = null)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.SYNC_CURRENT, source = ActionSource.VOICE), saved.copy(inspectionOpen = false)) is Verdict.Clarify)
    }

    @Test fun confirmationIsBoundToGenerationAndCancelDoesNotSend() {
        val pending = SyncConfirmation.All("session-a", listOf(SyncTarget("one", 123)))
        val state = AppState(screen = Screen.SETTINGS, syncGeneration = "session-a", pendingSync = pending)
        val confirm = ActionRequest(VoiceAction.CONFIRM, source = ActionSource.VOICE)
        assertEquals(listOf(Operation.ConfirmSync(pending)), (ActionPolicy.resolve(confirm, state) as Verdict.Run).operations)
        assertTrue(ActionPolicy.resolve(confirm, state.copy(syncGeneration = "session-b")) !is Verdict.Run)
        val no = VoiceCommands.parseForScreen("не отправляй", "settings")!!
        val cancelled = ActionPolicy.resolve(ActionRequest(no.action, no.value, ActionSource.VOICE), state) as Verdict.Run
        assertEquals(listOf(Operation.CancelSyncConfirmation), cancelled.operations)
    }

    @Test fun assistantCannotSubmitAllAndConfirmInSamePlan() {
        var state = AppState(screen = Screen.SETTINGS, syncGeneration = "session-a")
        val operations = mutableListOf<Operation>()
        val port = object : ActionPort {
            override fun state() = state
            override fun perform(operation: Operation) {
                operations += operation
                if (operation == Operation.AskSyncAll) state = state.copy(pendingSync = SyncConfirmation.All("session-a", listOf(SyncTarget("one", 1))))
            }
            override fun tell(text: String, isError: Boolean, request: ActionRequest?) { }
        }
        ActionDispatcher(port).submitPlan(listOf(ActionRequest(VoiceAction.SYNC_ALL, source = ActionSource.ASSISTANT),
            ActionRequest(VoiceAction.CONFIRM, source = ActionSource.ASSISTANT)))
        assertEquals(listOf(Operation.AskSyncAll), operations)
        assertTrue(NegationGuard.blocks("не отправляй осмотр", ActionRequest(VoiceAction.SYNC_CURRENT, source = ActionSource.ASSISTANT)))
    }

    @Test fun logoutRemainsAvailableDuringNetworkTaskAndDictationStaysText() {
        val request = ActionRequest(VoiceAction.SYNC_LOGOUT, source = ActionSource.VOICE)
        assertEquals(listOf(Operation.LogoutSync), (ActionPolicy.resolve(request, saved.copy(busy = true)) as Verdict.Run).operations)
        assertEquals(Verdict.Dictate, ActionPolicy.resolve(request, saved.copy(screen = Screen.NOTE)))
    }

    @Test fun freeSpeechAllowsOnlyExplicitInAppSyncAndNegationCancels() {
        val speech = com.agronomy.phytomonitor.voice.FreeSpeech.understand("отправь этот осмотр", "journal")
        assertEquals(VoiceAction.SYNC_CURRENT, (speech as com.agronomy.phytomonitor.voice.Understanding.Commands).commands.single().action)
        val cancel = com.agronomy.phytomonitor.voice.FreeSpeech.understand("не отправляй", "settings")
        assertEquals(VoiceAction.CANCEL, (cancel as com.agronomy.phytomonitor.voice.Understanding.Commands).commands.single().action)
        assertTrue(com.agronomy.phytomonitor.voice.FreeSpeech.understand("отправь фото по почте", "journal") is com.agronomy.phytomonitor.voice.Understanding.Refusal)
    }

    @Test fun conflictConfirmationCannotApplyToAnotherOpenedInspection() {
        val pending = SyncConfirmation.Conflict("session-a", SyncTarget("saved-1", 123), "{}")
        val request = ActionRequest(VoiceAction.CONFIRM, source = ActionSource.VOICE)
        assertTrue(ActionPolicy.resolve(request, saved.copy(pendingSync = pending, openedInspectionId = "saved-2")) is Verdict.Clarify)
    }
}
