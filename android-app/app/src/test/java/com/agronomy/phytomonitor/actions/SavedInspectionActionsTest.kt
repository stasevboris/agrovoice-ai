package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.FreeSpeech
import com.agronomy.phytomonitor.voice.Understanding
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class SavedInspectionActionsTest {
    private val journal = AppState(screen = Screen.JOURNAL, journalIds = listOf("filtered-b", "filtered-a"))
    private fun request(action: VoiceAction, value: String = "") = ActionRequest(action, value, ActionSource.VOICE)

    @Test
    fun numberUsesCurrentFilteredOrderAndButtonsShareTheOperation() {
        val request = request(VoiceAction.JOURNAL_NUMBER, "2")
        val expected = Verdict.Run(listOf(Operation.OpenInspection("filtered-a")))
        assertEquals(expected, ActionPolicy.resolve(request, journal))
        assertEquals(expected, ActionPolicy.resolve(request.copy(source = ActionSource.BUTTON), journal))
        assertEquals(Verdict.Run(listOf(Operation.OpenInspection("filtered-b"))),
            ActionPolicy.resolve(request, journal.copy(journalIds = journal.journalIds.reversed())))
        assertTrue(ActionPolicy.resolve(request, journal.copy(journalIds = listOf("filtered-b"))) is Verdict.Clarify)
    }

    @Test
    fun emptyMissingLoadingAndInvalidRequestsDoNotOpenAnything() {
        for (value in listOf("0", "-1", "3", "2147483648", "id", "")) {
            assertTrue(value, ActionPolicy.resolve(request(VoiceAction.JOURNAL_NUMBER, value), journal) is Verdict.Clarify)
        }
        val first = request(VoiceAction.JOURNAL_NUMBER, "1")
        assertTrue(ActionPolicy.resolve(first, journal.copy(journalIds = emptyList())) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(first, AppState()) is Verdict.Clarify)
        assertEquals(Verdict.Clarify(ActionPolicy.WAIT), ActionPolicy.resolve(first, journal.copy(busy = true)))
    }

    @Test
    fun readingUsesSavedIdWithoutPhotoOrCurrentAnalysisAndBackClosesOneLayer() {
        val opened = journal.copy(inspectionOpen = true, openedInspectionId = "filtered-b")
        assertEquals(Verdict.Run(listOf(Operation.SpeakInspection("filtered-b"))),
            ActionPolicy.resolve(request(VoiceAction.SPEAK_RESULT), opened))
        assertTrue(ActionPolicy.resolve(request(VoiceAction.SPEAK_RESULT),
            journal.copy(hasResult = true)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(request(VoiceAction.SPEAK_RESULT),
            opened.copy(openedInspectionId = null, hasResult = true)) is Verdict.Clarify)
        assertEquals(Verdict.Run(listOf(Operation.CloseInspection)), ActionPolicy.resolve(request(VoiceAction.BACK), opened))
        assertEquals(Verdict.Run(listOf(Operation.CloseJournal)), ActionPolicy.resolve(request(VoiceAction.BACK), journal))
    }

    @Test
    fun russianRequestsStayInJournalAndNegationDoesNotOpenARecord() {
        for (phrase in listOf("открой осмотр номер два", "запись два", "2")) {
            val command = VoiceCommands.parseForScreen(phrase, "journal")!!
            assertEquals(VoiceAction.JOURNAL_NUMBER, command.action)
            assertEquals("2", command.value)
        }
        assertNull(VoiceCommands.parseForScreen("открой осмотр два", "main"))
        assertNull(VoiceCommands.parseForScreen("фото два", "journal"))
        assertEquals(VoiceAction.SPEAK_RESULT, VoiceCommands.parseForScreen("прочитай результат", "journal")!!.action)
        assertTrue(FreeSpeech.understand("не открывай осмотр два", "journal") !is Understanding.Commands)
        val free = FreeSpeech.understand("пожалуйста покажи запись номер два", "journal") as Understanding.Commands
        assertEquals(VoiceAction.JOURNAL_NUMBER, free.commands.single().action)
        assertEquals(Decision.CLARIFY, AssistantProtocol.read(
            """{"decision":"execute","actions":[{"action":"JOURNAL_NUMBER","value":"2"}]}""",
            journal, "не открывай второй осмотр").decision)
        assertTrue(VoiceCommands.journalGrammar(2)!!.contains("два"))
    }
}
