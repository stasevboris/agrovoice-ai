package com.agronomy.phytomonitor.draft

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.*
import org.junit.Test

class SeriesActionsTest {
    private val current = AppState(hasPhoto = true, hasResult = true, frameCount = 2, selectedFrame = 1)
    private fun resolve(action: VoiceAction, value: String = "", state: AppState = current) =
        ActionPolicy.resolve(ActionRequest(action, value, ActionSource.VOICE), state)

    @Test
    fun addSelectAndRoleUseSameExecutorWithTypedOperations() {
        assertEquals(listOf(Operation.AddFrame(false, true)), (resolve(VoiceAction.ADD_FRAME) as Verdict.Run).operations)
        assertEquals(listOf(Operation.AddFrame(true, true)), (resolve(VoiceAction.ADD_FRAME_GALLERY) as Verdict.Run).operations)
        assertEquals(listOf(Operation.SelectFrame(2, false)), (resolve(VoiceAction.SELECT_FRAME, "2") as Verdict.Run).operations)
        assertEquals(listOf(Operation.SetFrameRole("back")), (resolve(VoiceAction.FRAME_ROLE, "back") as Verdict.Run).operations)
    }

    @Test
    fun busyIncompleteFullAndWrongScreenDoNotStartCapture() {
        listOf(current.copy(busy = true), current.copy(hasResult = false), current.copy(frameCount = 10),
            current.copy(screen = Screen.SETTINGS), current.copy(hasDraft = true)).forEach {
            assertTrue(resolve(VoiceAction.ADD_FRAME, state = it) is Verdict.Clarify)
        }
        assertTrue(resolve(VoiceAction.SELECT_FRAME, "3") is Verdict.Clarify)
        assertTrue(resolve(VoiceAction.FRAME_ROLE, "unknown") is Verdict.Reject)
    }

    @Test
    fun savedSeriesAllowsSelectionAndDictationCannotSelectFrames() {
        val journal = current.copy(screen = Screen.JOURNAL, inspectionOpen = true)
        assertEquals(listOf(Operation.SelectFrame(2, true)),
            (resolve(VoiceAction.SELECT_FRAME, "2", journal) as Verdict.Run).operations)
        assertTrue(resolve(VoiceAction.ADD_FRAME, state = journal) is Verdict.Clarify)
        assertEquals(Verdict.Dictate, resolve(VoiceAction.SELECT_FRAME, "2", current.copy(screen = Screen.NOTE)))
    }

    @Test
    fun assistantCannotAddFrameFromNegatedRequest() {
        val json = """{"decision":"execute","actions":[{"action":"ADD_FRAME","value":""}]}"""
        assertEquals(Decision.CLARIFY, AssistantProtocol.read(json, current, "не добавляй кадр").decision)
    }
}
