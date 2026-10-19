package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class PlantActionsTest {
    private fun resolve(action: VoiceAction, value: String = "", state: AppState = AppState()) =
        ActionPolicy.resolve(ActionRequest(action, value, ActionSource.VOICE), state)

    @Test fun plantNamesAreLiteralAndDictationDoesNotExecuteThem() {
        assertEquals("куст семь", VoiceCommands.parse("это растение куст семь")!!.value)
        assertNull(VoiceCommands.parse("не выбирай растение куст семь"))
        assertEquals(Verdict.Dictate, resolve(VoiceAction.PLANT, "куст", AppState(screen = Screen.NOTE)))
        assertTrue(resolve(VoiceAction.PLANT, "куст", AppState(screen = Screen.JOURNAL)) is Verdict.Clarify)
        assertEquals(Verdict.Run(listOf(Operation.SetPlant(""))), resolve(VoiceAction.PLANT))
    }

    @Test fun comparisonUsesOnlyCurrentHistoryAndChecksBothNumbers() {
        val state = AppState(screen = Screen.JOURNAL, plantHistoryOpen = true, journalIds = listOf("older", "newer"))
        val command = VoiceCommands.parseForScreen("сравни осмотры один и два", "journal")!!
        assertEquals("1:2", command.value)
        assertEquals(Verdict.Run(listOf(Operation.CompareInspections("older", "newer"))), resolve(command.action, command.value, state))
        for (value in listOf("1:1", "1:3", "0:2", "2147483648:2", "bad")) {
            assertTrue(resolve(command.action, value, state) is Verdict.Clarify)
        }
        assertTrue(resolve(command.action, "1:2") is Verdict.Clarify)
        assertEquals(Verdict.Run(listOf(Operation.ClosePlantHistory)), resolve(VoiceAction.BACK, state = state))
        assertEquals(Verdict.Run(listOf(Operation.SpeakPlantHistory)), resolve(VoiceAction.SPEAK_RESULT, state = state))
    }
}
