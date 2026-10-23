package com.agronomy.phytomonitor.knowledge

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class KnowledgeCatalogTest {
    @Test
    fun cardNumbersAreContextualAndCheckedAgainstVisibleCards() {
        val command = VoiceCommands.parseForScreen("открой карточку два", "knowledge")!!
        assertEquals(VoiceAction.KNOWLEDGE_NUMBER, command.action)
        assertNull(VoiceCommands.parseForScreen("не открывай карточку два", "knowledge"))
        assertNull(VoiceCommands.parseForScreen("карточка два", "note"))
        val request = ActionRequest(command.action, command.value, ActionSource.VOICE)
        assertEquals(Verdict.Run(listOf(Operation.OpenKnowledgeCard("b", true))), ActionPolicy.resolve(request,
            AppState(screen = Screen.KNOWLEDGE, knowledgeCodes = listOf("a", "b"))))
        assertTrue(ActionPolicy.resolve(request, AppState(screen = Screen.KNOWLEDGE, knowledgeCodes = listOf("a"))) is Verdict.Clarify)
    }

    private fun catalog(): KnowledgeCatalog {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "kb/kb_v1.json").isFile }
        return KnowledgeCatalog.parse(File(root, "kb/kb_v1.json").readText())
    }

    @Test
    fun searchKeepsCropBoundariesAndDraftTextIsNeverSpokenAsAdvice() {
        val catalog = catalog()
        val found = catalog.search("ФИТОФТОРОЗ", "potato")
        assertEquals(listOf("potato__late_blight"), found.map { it.code })
        assertTrue(catalog.search("фитофтороз", "apple").isEmpty())
        assertTrue(found.single().sections.getValue("symptoms").isNotEmpty())
        assertFalse(found.single().reviewed())
        assertTrue(found.single().spoken().contains("не проверена"))
        assertFalse(found.single().spoken().contains(found.single().sections.getValue("symptoms").first().text))
    }

    @Test
    fun expiredVerificationAndMissingExpertBlockNarration() {
        val draft = catalog().cards.first { it.code == "potato__late_blight" }
        // Dates follow the card's newest source access, so refreshing the KB does not age this test.
        val day = draft.sources.maxOf { LocalDate.parse(it.accessed) }
        val card = draft.copy(status = "verified", verifiedBy = "TEST: специалист",
            verifiedAt = day.toString(), reviewDue = day.plusDays(1).toString(), missing = emptyList())
        assertTrue(card.reviewed(day))
        assertFalse(card.reviewed(day.plusDays(2)))
        assertFalse(card.copy(verifiedBy = null).reviewed(day))
        assertFalse(card.copy(missing = listOf("treatments")).reviewed(day))
        assertFalse(card.copy(sources = emptyList()).reviewed(day))
    }

    @Test
    fun choosingKnowledgeCropDoesNotChangeTheAnalysisModelOrRunAnalysis() {
        val request = ActionRequest(VoiceAction.CROP, "potato", ActionSource.VOICE)
        assertEquals(Verdict.Run(listOf(Operation.FilterKnowledgeCrop("potato"))),
            ActionPolicy.resolve(request, AppState(screen = Screen.KNOWLEDGE, crops = listOf("potato"), hasPhoto = true, busy = true)))
        val ask = ActionRequest(VoiceAction.READ_KNOWLEDGE, source = ActionSource.VOICE)
        assertTrue(ActionPolicy.resolve(ask, AppState(hasResult = true)) is Verdict.Clarify)
    }
}
