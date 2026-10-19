package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.ml.CropVocabulary
import com.agronomy.phytomonitor.voice.FreeSpeech
import com.agronomy.phytomonitor.voice.Understanding
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * V-06 comparison on ml-pipeline/assistant/scenarios.json, without training: (1) the exact-phrase rules of
 * the 0.18 app, (2) the free-phrase rules of FreeSpeech. Both go through the same executor. Reports are
 * written to app/build/reports/v06/. The phrases are authored and synthetic; FreeSpeech was written with
 * all splits visible, so its numbers are a development check, not an independent test.
 */
class RulesBaselineTest {
    private fun find(relative: String): File {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null && !File(folder, relative).exists()) {
            folder = folder.parentFile
        }
        return File(requireNotNull(folder) { "$relative not found" }, relative)
    }

    private class Counts {
        var executeTotal = 0
        var executeCorrect = 0
        var executeMissed = 0
        var executeWrong = 0
        var refuseTotal = 0
        var refuseCorrect = 0
        var falseExecutions = 0

        fun json(): JSONObject = JSONObject()
            .put("execute_utterances", executeTotal)
            .put("execute_exact", executeCorrect)
            .put("execute_no_action", executeMissed)
            .put("execute_wrong_action", executeWrong)
            .put("non_execute_utterances", refuseTotal)
            .put("non_execute_not_executed", refuseCorrect)
            .put("false_executions", falseExecutions)
    }

    @Test
    fun exactPhraseRules() {
        val report = evaluate("VoiceCommands.parseForScreen (exact phrases of app 0.18) + ActionPolicy") { utterance, state ->
            listOfNotNull(VoiceCommands.parseForScreen(utterance, state.screen.protocolName, state.plots))
        }
        write("rules_exact_phrases.json", report)
        assertEquals(report.getJSONArray("false_execution_examples").toString(), 0, report.getJSONArray("false_execution_examples").length())
    }

    @Test
    fun freePhraseRules() {
        val report = evaluate("FreeSpeech.understand (stems, numbers, negation, connectors) + ActionPolicy") { utterance, state ->
            when (val understood = FreeSpeech.understand(utterance, state.screen.protocolName, state.plots)) {
                is Understanding.Commands -> understood.commands
                else -> emptyList()
            }
        }
        write("rules_free_phrases.json", report)
        // Safety stays a hard requirement for the free rules too.
        assertEquals(report.getJSONArray("false_execution_examples").toString(), 0, report.getJSONArray("false_execution_examples").length())
    }

    /**
     * Zero-shot answers of local LLMs collected by ml-pipeline/assistant/run_ollama_router.py. Each answer
     * goes through AssistantProtocol (structure, dictation, negation guard) and the executor, as in the app.
     */
    @Test
    fun localLlmAnswers() {
        val folder = find("docs/experiments/v06_intent_baseline")
        val files = folder.listFiles { file -> file.name.startsWith("llm_answers_") && file.name.endsWith(".json") }.orEmpty()
        for (file in files.sortedBy { it.name }) {
            val array = JSONArray(file.readText())
            val rows = (0 until array.length()).map { array.getJSONObject(it) }
            val answers = rows.associate { it.getString("utterance") to it.getString("answer") }
            val latencies = rows.map { it.getLong("latency_ms") }.sorted()
            val model = rows.first().getString("model")
            val report = evaluate("$model zero-shot via Ollama, JSON schema decoding, temperature 0 + AssistantProtocol + ActionPolicy") { utterance, state ->
                val answer = answers.getValue(utterance)
                val plan = AssistantProtocol.read(answer, state, utterance)
                if (plan.decision != Decision.EXECUTE) {
                    emptyList()
                } else {
                    plan.steps.map { VoiceCommand(it.action, it.action.name, it.value) }
                }
            }
            report.put("raw_execute_proposals_on_non_execute", rawExecuteProposals(answers))
            report.put("desktop_latency_ms_median", latencies[latencies.size / 2])
            report.put("desktop_latency_note", "Ollama on the development PC, first call includes loading; not a phone measurement.")
            write("llm_${file.name.removePrefix("llm_answers_").removeSuffix(".json")}.json", report)
            assertEquals(201, rows.size)
        }
    }

    /** Before the executor: how often the model itself said «execute» where the corpus expects no action. */
    private fun rawExecuteProposals(answers: Map<String, String>): Int {
        val scenarios = JSONObject(find("ml-pipeline/assistant/scenarios.json").readText()).getJSONArray("scenarios")
        var count = 0
        for (index in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(index)
            if (scenario.optString("decision", "execute") == "execute") {
                continue
            }
            val utterances = scenario.getJSONArray("utterances")
            for (u in 0 until utterances.length()) {
                val answer = answers.getValue(utterances.getString(u))
                val decision = runCatching { JSONObject(answer).optString("decision") }.getOrDefault("")
                if (decision == "execute") {
                    count += 1
                }
            }
        }
        return count
    }

    private fun evaluate(method: String, understand: (String, AppState) -> List<VoiceCommand>): JSONObject {
        val scenarios = JSONObject(find("ml-pipeline/assistant/scenarios.json").readText()).getJSONArray("scenarios")
        val bySplit = linkedMapOf("train" to Counts(), "val" to Counts(), "test" to Counts())
        val falseExamples = JSONArray()
        val misses = JSONArray()
        for (index in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(index)
            val counts = bySplit.getValue(scenario.getString("split"))
            val state = state(scenario)
            val expected = expectedActions(scenario)
            val utterances = scenario.getJSONArray("utterances")
            for (u in 0 until utterances.length()) {
                val utterance = utterances.getString(u)
                val executed = execute(understand(utterance, state), state)
                if (expected.isNotEmpty()) {
                    counts.executeTotal += 1
                    when {
                        executed == expected -> counts.executeCorrect += 1
                        executed.isEmpty() -> counts.executeMissed += 1
                        else -> counts.executeWrong += 1
                    }
                    if (executed != expected) {
                        misses.put(JSONObject().put("id", scenario.getString("id")).put("utterance", utterance).put("got", executed.toString()))
                    }
                } else {
                    counts.refuseTotal += 1
                    if (executed.isEmpty()) {
                        counts.refuseCorrect += 1
                    } else {
                        counts.falseExecutions += 1
                        falseExamples.put(JSONObject().put("id", scenario.getString("id")).put("utterance", utterance).put("got", executed.toString()))
                    }
                }
            }
        }
        val report = JSONObject()
            .put("method", method)
            .put("source", "ml-pipeline/assistant/scenarios.json")
            .put("command", "gradlew testDebugUnitTest --tests *RulesBaselineTest")
            .put("limitations", JSONArray()
                .put("Authored synthetic Russian phrases; not recorded speech and not ASR output.")
                .put("Exact phrases: Vosk grammar mode can only output grammar phrases, so paraphrases are not even transcribed.")
                .put("Free phrases need Vosk without a grammar; its recognition errors are not included here.")
                .put("FreeSpeech rules were written with every split of the corpus visible: no independent test."))
        bySplit.forEach { (split, counts) -> report.put(split, counts.json()) }
        report.put("false_execution_examples", falseExamples)
        report.put("execute_misses", misses)
        return report
    }

    /** A plan runs only if every step is allowed in the scenario state; refused steps run nothing. */
    private fun execute(commands: List<VoiceCommand>, state: AppState): List<Pair<String, String>> {
        if (commands.isEmpty()) {
            return emptyList()
        }
        val allowed = commands.all { command ->
            val verdict = ActionPolicy.resolve(ActionRequest(command.action, command.value, ActionSource.VOICE), state)
            verdict is Verdict.Run || verdict is Verdict.Skip
        }
        return if (allowed) commands.map { it.action.name to it.value } else emptyList()
    }

    private fun write(name: String, report: JSONObject) {
        val output = File(System.getProperty("user.dir") ?: ".", "build/reports/v06/$name")
        output.parentFile!!.mkdirs()
        output.writeText(report.toString(2))
    }

    private fun expectedActions(scenario: JSONObject): List<Pair<String, String>> {
        if (scenario.optString("decision", "execute") != "execute") {
            return emptyList()
        }
        val items = scenario.getJSONArray("actions")
        return (0 until items.length()).map { items.getJSONArray(it) }.map { it.getString(0) to it.getString(1) }
    }

    private fun state(scenario: JSONObject): AppState {
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
            crop = "",
            crops = CropVocabulary.names.keys.toList(),
            availableModels = listOf("yolo26n", "yolo26s", "yolo11n")
        )
    }
}
