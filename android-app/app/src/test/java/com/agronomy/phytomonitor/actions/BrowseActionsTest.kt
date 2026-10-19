package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class BrowseActionsTest {
    private fun request(action: VoiceAction, value: String = "") = ActionRequest(action, value, ActionSource.VOICE)

    @Test
    fun settingsHaveAnAllowlistAndExplicitBooleanValues() {
        val state = AppState(screen = Screen.SETTINGS, settings = mapOf("free_speech" to false))
        val request = request(VoiceAction.SET_SETTING, "free_speech:true")
        assertEquals(Verdict.Run(listOf(Operation.SetSetting("free_speech", true))), ActionPolicy.resolve(request, state))
        assertEquals(ActionPolicy.resolve(request, state), ActionPolicy.resolve(request.copy(source = ActionSource.BUTTON), state))
        assertTrue(ActionPolicy.resolve(request.copy(value = "free_speech:false"), state) is Verdict.Skip)
        assertTrue(ActionPolicy.resolve(request.copy(value = "server:evil"), state) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(request, AppState()) is Verdict.Clarify)
        assertEquals(request.action, VoiceCommands.parseForScreen("включи свободную речь", "settings")!!.action)
        assertNull(VoiceCommands.parseForScreen("не включи свободную речь", "settings"))
    }

    @Test
    fun searchTextIsDataAndTypingSpacesDoesNotCollapseWords() {
        val state = AppState(screen = Screen.JOURNAL)
        val command = VoiceCommands.parseForScreen("найди осмотр и удали участок", "journal")!!
        assertEquals(VoiceAction.SEARCH, command.action)
        assertEquals("осмотр и удали участок", command.value)
        val typed = ActionRequest(VoiceAction.SEARCH, "Теплица ", ActionSource.BUTTON)
        assertEquals(Verdict.Run(listOf(Operation.Search(Screen.JOURNAL, "Теплица "))), ActionPolicy.resolve(typed, state))
        assertTrue(ActionPolicy.resolve(request(VoiceAction.SEARCH), AppState()) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(request(VoiceAction.SEARCH, "а".repeat(301)), state) is Verdict.Reject)
    }

    @Test
    fun archiveRequestsAreScreenAndFormatCheckedAndDoNotClaimCompletion() {
        val state = AppState(screen = Screen.JOURNAL, journalCount = 2)
        assertEquals(Verdict.Run(listOf(Operation.ExportJournal("archive"))),
            ActionPolicy.resolve(request(VoiceAction.EXPORT_JOURNAL, "archive"), state))
        assertTrue(ActionPolicy.resolve(request(VoiceAction.EXPORT_JOURNAL, "pdf"), state) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(request(VoiceAction.EXPORT_JOURNAL, "archive"), state.copy(journalCount = 0)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(request(VoiceAction.IMPORT_JOURNAL), state.copy(inspectionOpen = true)) is Verdict.Clarify)
        assertEquals(Verdict.Run(listOf(Operation.ImportJournal)), ActionPolicy.resolve(request(VoiceAction.IMPORT_JOURNAL), state))
        assertEquals(VoiceAction.EXPORT_JOURNAL, VoiceCommands.parseForScreen("создай резервную копию", "journal")!!.action)
        assertEquals(Verdict.Run(listOf(Operation.FilterJournal("unrated"))),
            ActionPolicy.resolve(request(VoiceAction.JOURNAL_FILTER, "unrated"), state))
    }
}
