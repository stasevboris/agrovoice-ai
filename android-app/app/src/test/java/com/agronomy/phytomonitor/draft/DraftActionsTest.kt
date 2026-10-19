package com.agronomy.phytomonitor.draft

import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.actions.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DraftActionsTest {
    @Test
    fun explicitDraftCommandsAreRecognizedAndNegationsAreNot() {
        assertNotNull(VoiceCommands.parseForScreen("продолжить осмотр", "main"))
        assertNotNull(VoiceCommands.parseForScreen("отменить черновик", "main"))
        assertEquals(VoiceAction.DISCARD_DRAFT, VoiceCommands.parseForScreen("начать новый осмотр", "main")?.action)
        assertNull(VoiceCommands.parseForScreen("не начинай новый осмотр", "main"))
        assertNull(VoiceCommands.parseForScreen("не отменяй черновик", "main"))
    }

    @Test
    fun draftActionsUseTheSamePolicyForButtonsAndVoice() {
        for (source in listOf(ActionSource.BUTTON, ActionSource.VOICE)) {
            val resume = ActionRequest(VoiceAction.RESUME_DRAFT, source = source)
            assertEquals(listOf(Operation.ResumeDraft),
                (ActionPolicy.resolve(resume, AppState(hasDraft = true)) as Verdict.Run).operations)
            assertTrue(ActionPolicy.resolve(resume, AppState()) is Verdict.Skip)
            assertTrue(ActionPolicy.resolve(resume, AppState(hasDraft = true, busy = true)) is Verdict.Clarify)
            assertEquals(listOf(Operation.DiscardDraft), (ActionPolicy.resolve(
                ActionRequest(VoiceAction.DISCARD_DRAFT, source = source), AppState(hasDraft = true)) as Verdict.Run).operations)
        }
        assertTrue(VoiceCommands.grammar.contains("продолжить осмотр"))
        assertNull(VoiceCommands.parseForScreen("отменить черновик", "journal"))
    }
}
