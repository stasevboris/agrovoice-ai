package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs every scenario of ml-pipeline/assistant/scenarios.json through the Android protocol and policy.
 * This checks that the app enforces the corpus rules; it is not an accuracy measurement of any router.
 */
class AssistantCorpusTest {
    private val scenarios: List<JSONObject> = loadScenarios()

    /**
     * For each scenario that must not execute: the action a careless router might still propose.
     * A new clarify/reject scenario fails the test until its tempting action is listed here.
     */
    private val temptingActions = mapOf(
        "missing_photo" to listOf("ANALYZE" to ""),
        "missing_result" to listOf("SPEAK_RESULT" to ""),
        "save_unready" to listOf("SAVE_INSPECTION" to ""),
        "photo_bounds" to listOf("PHOTO_NUMBER" to "8"),
        "ambiguous_yes" to listOf("CONFIRM" to ""),
        "threshold_invalid" to listOf("THRESHOLD" to "120", "THRESHOLD" to "-5"),
        "unsupported_crop" to listOf("CROP" to "Bean", "CROP" to "Rice"),
        "unknown_model" to listOf("MODEL" to "dfine_n"),
        "negation" to listOf("SAVE_INSPECTION" to "", "OPEN_CAMERA" to "", "LISTEN_OFF" to ""),
        "unsupported_delete" to listOf("DELETE_PHOTOS" to "", "DELETE_JOURNAL" to "", "SEND_DATA" to ""),
        "treatment" to listOf("RECOMMEND_TREATMENT" to ""),
        "chat" to listOf("WEATHER" to ""),
        "injection" to listOf("DELETE_JOURNAL" to "", "SAVE_INSPECTION" to ""),
        "val_no_save" to listOf("SAVE_INSPECTION" to ""),
        "val_missing" to listOf("ANALYZE" to "", "SPEAK_RESULT" to ""),
        "val_dose" to listOf("RECOMMEND_TREATMENT" to ""),
        "val_wrong_screen" to listOf("PHOTO_NUMBER" to "5"),
        "test_negation" to listOf("OPEN_CAMERA" to "", "SAVE_INSPECTION" to "", "LISTEN_OFF" to ""),
        "test_bounds" to listOf("PHOTO_NUMBER" to "21", "PHOTO_NUMBER" to "0"),
        "test_yes_no_context" to listOf("CONFIRM" to ""),
        "test_system" to listOf("UNLOCK_PHONE" to "", "OPEN_SETTINGS" to "", "SEND_PHOTOS" to ""),
        "test_knowledge" to listOf("RECOMMEND_TREATMENT" to ""),
        "test_missing_analysis" to listOf("SAVE_INSPECTION" to "", "SPEAK_RESULT" to "")
    )

    @Test
    fun corpusActionsExistInTheApp() {
        val names = VoiceAction.entries.map { it.name }.toSet()
        scenarios.flatMap { actionsOf(it) }.forEach { (action, _) -> assertTrue(action, action in names) }
    }

    @Test
    fun stateJsonHasEveryKeyTheCorpusUses() {
        val emitted = AssistantProtocol.stateJson(AppState()).keys().asSequence().toSet()
        scenarios.mapNotNull { it.optJSONObject("state") }.flatMap { it.keys().asSequence().toList() }.forEach {
            assertTrue(it, it in emitted)
        }
        scenarios.forEach { assertNotNull(it.getString("screen"), Screen.fromProtocol(it.getString("screen"))) }
    }

    @Test
    fun expectedActionsAreAllowedInTheirState() {
        scenarios.filter { it.optString("decision", "execute") == "execute" }.forEach { scenario ->
            val state = stateOf(scenario)
            val target = targetJson(actionsOf(scenario))
            utterances(scenario).forEach { utterance ->
                val plan = AssistantProtocol.read(target, state, utterance)
                assertEquals("${scenario.getString("id")}: $utterance", Decision.EXECUTE, plan.decision)
                plan.steps.forEach { step ->
                    val verdict = ActionPolicy.resolve(step, state)
                    val allowed = verdict is Verdict.Run || verdict is Verdict.Skip
                    assertTrue("${scenario.getString("id")}: ${step.action} -> $verdict", allowed)
                }
            }
        }
    }

    @Test
    fun forbiddenProposalsAreNotExecuted() {
        val refusing = scenarios.filter { it.optString("decision") in setOf("clarify", "reject") }
        refusing.forEach { scenario ->
            val id = scenario.getString("id")
            val proposals = temptingActions[id]
            assertNotNull("add a tempting action for $id", proposals)
            val state = stateOf(scenario)
            utterances(scenario).forEach { utterance ->
                proposals!!.forEach { proposal ->
                    val plan = AssistantProtocol.read(targetJson(listOf(proposal)), state, utterance)
                    val executed = plan.decision == Decision.EXECUTE &&
                        plan.steps.all { ActionPolicy.resolve(it, state) is Verdict.Run }
                    val negationCase = id.contains("negation") || id == "val_no_save"
                    if (!negationCase || mentions(utterance, proposal.first)) {
                        assertFalse("$id: «$utterance» executed ${proposal.first}", executed)
                    }
                }
            }
        }
    }

    @Test
    fun dictatedTextStaysTextForAnyProposal() {
        val dictation = scenarios.filter { it.optString("decision") == "dictate" }
        assertTrue(dictation.isNotEmpty())
        dictation.forEach { scenario ->
            val state = stateOf(scenario)
            utterances(scenario).forEach { utterance ->
                val proposals = listOf(
                    targetJson(listOf("SAVE_INSPECTION" to "")),
                    targetJson(listOf("OPEN_CAMERA" to "")),
                    """{"decision":"reject","actions":[],"text":"нет"}"""
                )
                proposals.forEach {
                    val plan = AssistantProtocol.read(it, state, utterance)
                    assertEquals(Decision.DICTATE, plan.decision)
                    assertEquals(utterance, plan.text)
                }
                assertEquals(null, VoiceCommands.parseForScreen(utterance, "note"))
            }
        }
    }

    /** The current exact-phrase rules: none of the refused or dictated phrases may run an action. */
    @Test
    fun fixedVoicePhrasesNeverExecuteRefusedScenarios() {
        scenarios.filter { it.optString("decision", "execute") != "execute" }.forEach { scenario ->
            val state = stateOf(scenario)
            utterances(scenario).forEach { utterance ->
                val command = VoiceCommands.parseForScreen(utterance, state.screen.protocolName)
                if (command != null) {
                    val request = ActionRequest(command.action, command.value, ActionSource.VOICE)
                    val verdict = ActionPolicy.resolve(request, state)
                    assertFalse("${scenario.getString("id")}: «$utterance» -> $verdict", verdict is Verdict.Run)
                }
            }
        }
    }

    /** Negation scenarios list three phrases with different verbs; each tempting action belongs to one. */
    private fun mentions(utterance: String, action: String): Boolean {
        val text = utterance.lowercase()
        return when (action) {
            "SAVE_INSPECTION" -> listOf("сохран", "записыва", "истори").any { it in text }
            "OPEN_CAMERA" -> "камер" in text
            "LISTEN_OFF" -> listOf("микрофон", "слуша").any { it in text }
            else -> true
        }
    }

    private fun stateOf(scenario: JSONObject): AppState {
        val data = scenario.optJSONObject("state") ?: JSONObject()
        val selected = data.opt("selected_photo")
        return AppState(
            screen = Screen.fromProtocol(scenario.getString("screen"))!!,
            hasPhoto = data.optBoolean("has_photo"),
            hasResult = data.optBoolean("has_result"),
            busy = data.optBoolean("analysis_running"),
            galleryCount = data.optInt("gallery_count"),
            selectedPhoto = if (selected is Int) selected else null,
            speaking = data.optBoolean("speaking"),
            helpOpen = data.optBoolean("help_open"),
            verifiedKnowledge = data.optBoolean("verified_kb"),
            crop = "",
            availableModels = listOf("yolo26n", "yolo26s", "yolo11n")
        )
    }

    private fun actionsOf(scenario: JSONObject): List<Pair<String, String>> {
        val items = scenario.optJSONArray("actions") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONArray(it) }.map { it.getString(0) to it.getString(1) }
    }

    private fun utterances(scenario: JSONObject): List<String> {
        val items = scenario.getJSONArray("utterances")
        return (0 until items.length()).map { items.getString(it) }
    }

    private fun targetJson(actions: List<Pair<String, String>>): String {
        val items = JSONArray()
        actions.forEach { (action, value) -> items.put(JSONObject().put("action", action).put("value", value)) }
        return JSONObject().put("decision", "execute").put("actions", items).put("text", "").toString()
    }

    private fun loadScenarios(): List<JSONObject> {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null && !File(folder, CORPUS).isFile) {
            folder = folder.parentFile
        }
        val file = File(requireNotNull(folder) { "$CORPUS not found" }, CORPUS)
        val items = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("scenarios")
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    private companion object {
        const val CORPUS = "ml-pipeline/assistant/scenarios.json"
    }
}
