package com.agronomy.phytomonitor.knowledge

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class KnowledgeResultTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "kb/kb_v1.json").isFile }
    private fun document() = JSONObject(File(root, "kb/kb_v1.json").readText())

    @Test
    fun oldClassLinksMatchTaxonomyExceptUnknownCucumberWhichMustNotBecomeHealthy() {
        val links = JSONObject(File(root, "android-app/app/src/main/assets/knowledge/legacy_classes_v1.json").readText())
        val classes = JSONObject(File(root, "android-app/app/src/main/assets/models/manifest.json").readText()).getJSONArray("classes")
        val taxonomy = JSONObject(File(root, "ml-pipeline/allcrops/taxonomy_v1.json").readText())
            .getJSONObject("source_mapping").getJSONObject("PlantDocLegacy")
        for (index in 0 until classes.length() - 1) assertEquals(taxonomy.getString(index.toString()), links.getString(classes.getString(index)))
        val state = KnowledgeState(catalog = KnowledgeCatalog.parse(document().toString()),
            legacyClasses = links.keys().asSequence().associateWith(links::getString))
        assertEquals("potato__late_blight", state.codeForClass("Potato leaf late blight"))
        assertEquals("potato__late_blight", state.codeForClass("potato__late_blight"))
        assertNull(state.codeForClass("Cucumber leaf (condition unknown)"))
        assertNull(state.codeForClass("unknown"))
    }

    @Test
    fun eachLeafNeedsItsOwnCardAndUnknownOrEmptyResultsRefuse() {
        val ask = ActionRequest(VoiceAction.READ_KNOWLEDGE, source = ActionSource.VOICE)
        val state = AppState(resultKnowledgeCodes = listOf("potato__late_blight"))
        assertEquals(Verdict.Run(listOf(Operation.OpenResultKnowledge("potato__late_blight", true))), ActionPolicy.resolve(ask, state))
        val multiple = state.copy(resultKnowledgeCodes = listOf(null, "potato__late_blight"), screen = Screen.JOURNAL, inspectionOpen = true)
        assertTrue(ActionPolicy.resolve(ask, multiple) is Verdict.Clarify)
        val second = VoiceCommands.parseForScreen("справка по листу два", "journal")!!
        assertEquals(ActionPolicy.resolve(ask, state), ActionPolicy.resolve(ActionRequest(second.action, second.value, ActionSource.VOICE), multiple))
        assertTrue(ActionPolicy.resolve(ask, state.copy(resultKnowledgeCodes = listOf(null))) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(ask, AppState()) is Verdict.Clarify)
    }

    private fun withTestProduct(): JSONObject {
        val document = document()
        document.getJSONArray("sources").put(JSONObject().put("id", "T99").put("title", "TEST: не для применения")
            .put("url", "https://example.invalid/register").put("edition", "TEST").put("kind", "by_register")
            .put("region", "BY").put("accessed", "2026-10-19").put("rights", "TEST"))
        val card = document.getJSONArray("cards").getJSONObject(0)
        card.getJSONArray("sources").put("T99")
        card.getJSONArray("treatments").put(JSONObject().put("product", "TEST_PRODUCT").put("active_substance", "TEST_ACTIVE")
            .put("rate", "TEST_RATE").put("unit", "TEST_UNIT").put("registered_in", "BY").put("register_year", 2026)
            .put("register_entry", "TEST_ENTRY").put("user_category", "professional").put("source", "T99").put("locator", "TEST_PAGE"))
        return document
    }

    @Test
    fun productsStaySilentUntilExpertDatesAndSourcesAreValid() {
        val card = KnowledgeCatalog.parse(withTestProduct().toString()).cards.first()
        val today = LocalDate.parse("2026-10-19")
        assertEquals("TEST_RATE", card.treatments.single().rate)
        assertFalse(card.spoken(today).contains("TEST_RATE"))
        val verified = card.copy(status = "verified", verifiedBy = "TEST", verifiedAt = "2026-10-19",
            reviewDue = "2026-10-20", missing = emptyList())
        assertTrue(verified.spoken(today).contains("TEST_RATE TEST_UNIT"))
        assertTrue(verified.spoken(today).contains("профессионального"))
        assertFalse(verified.spoken(today.plusDays(2)).contains("TEST_RATE"))
        assertFalse(verified.copy(verifiedAt = null).reviewed(today))
        assertFalse(verified.copy(verifiedAt = "2026-10-20").reviewed(today))
    }

    @Test
    fun foreignRegionMissingRateOrNonRegistrySourceRejectProducts() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("registered_in", "RU") }, { it.remove("rate") }, { it.put("source", "absent") },
            { it.put("user_category", "any") }, { it.put("waiting_days", -1) }
        )) {
            val document = withTestProduct()
            change(document.getJSONArray("cards").getJSONObject(0).getJSONArray("treatments").getJSONObject(0))
            assertTrue(runCatching { KnowledgeCatalog.parse(document.toString()) }.isFailure)
        }
        val document = withTestProduct()
        val sources = document.getJSONArray("sources")
        sources.getJSONObject(sources.length() - 1).put("kind", "textbook")
        assertTrue(runCatching { KnowledgeCatalog.parse(document.toString()) }.isFailure)
    }
}
