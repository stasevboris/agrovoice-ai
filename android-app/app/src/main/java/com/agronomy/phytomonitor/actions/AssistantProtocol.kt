package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class Decision(val protocolName: String) {
    EXECUTE("execute"),
    CLARIFY("clarify"),
    REJECT("reject"),
    DICTATE("dictate")
}

/** A router decision after the app's own checks. Steps are a plan, not a report of what was done. */
data class AssistantPlan(
    val decision: Decision,
    val steps: List<ActionRequest> = emptyList(),
    val text: String = ""
)

/**
 * The JSON protocol of ml-pipeline/assistant/prepare_corpus.py on the Android side.
 * Input to a router: [stateJson] + utterance. Output of a router: `{"decision", "actions", "text"}`.
 * Nothing here runs an action: [ActionDispatcher] executes the reviewed plan step by step.
 */
object AssistantProtocol {
    const val MAX_STEPS = 3
    const val MAX_TEXT = 300
    const val MALFORMED = "Ответ помощника не соответствует протоколу."
    const val UNSUPPORTED = "Это действие не поддерживается приложением."
    const val NEGATED = "В команде есть отрицание. Если действие нужно, повтори его без «не»."

    /** The same keys as the `state` object of the corpus. */
    fun stateJson(state: AppState): JSONObject = JSONObject()
        .put("screen", state.screen.protocolName)
        .put("has_photo", state.hasPhoto)
        .put("has_result", state.hasResult)
        .put("analysis_running", state.busy)
        .put("gallery_count", state.galleryCount)
        .put("selected_photo", state.selectedPhoto ?: JSONObject.NULL)
        .put("journal_count", state.journalIds.size)
        .put("inspection_open", state.inspectionOpen)
        .put("saved_result_ready", state.openedInspectionId != null)
        .put("pending", state.pending ?: JSONObject.NULL)
        .put("plots", JSONArray(state.plots))
        .put("pending_plot_name", state.pendingPlotDeletion?.name ?: JSONObject.NULL)
        .put("settings", JSONObject(state.settings))
        .put("speaking", state.speaking)
        .put("help_open", state.helpOpen)
        .put("verified_kb", state.verifiedKnowledge)
        .put("available_models", JSONArray(state.availableModels))

    /** Parses and reviews one router answer for the given state and utterance. Never throws. */
    fun read(json: String, state: AppState, utterance: String): AssistantPlan {
        val parsed = try {
            parse(json)
        } catch (error: JSONException) {
            AssistantPlan(Decision.REJECT, text = MALFORMED)
        }
        return review(parsed, state, utterance)
    }

    /** Structure only: known decision, known actions, string values, actions only with «execute». */
    fun parse(json: String): AssistantPlan {
        val data = JSONObject(json)
        val decision = Decision.entries.firstOrNull { it.protocolName == data.optString("decision") }
            ?: return AssistantPlan(Decision.REJECT, text = MALFORMED)
        val items = data.optJSONArray("actions") ?: JSONArray()
        val text = data.optString("text").take(MAX_TEXT)
        if ((decision == Decision.EXECUTE) != (items.length() > 0)) {
            return AssistantPlan(Decision.REJECT, text = MALFORMED)
        }
        val steps = mutableListOf<ActionRequest>()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val action = VoiceAction.entries.firstOrNull { it.name == item.optString("action") }
                ?: return AssistantPlan(Decision.REJECT, text = UNSUPPORTED)
            val value = item.opt("value") ?: ""
            if (value !is String) {
                return AssistantPlan(Decision.REJECT, text = MALFORMED)
            }
            steps += ActionRequest(action, value, ActionSource.ASSISTANT)
        }
        return AssistantPlan(decision, steps, text)
    }

    /**
     * Rules that do not trust the model: a dictated note stays text, a negated phrase does not start
     * a side effect, the first step must be possible now. Later steps are checked again when they run.
     */
    fun review(plan: AssistantPlan, state: AppState, utterance: String): AssistantPlan {
        if (state.screen == Screen.NOTE) {
            return AssistantPlan(Decision.DICTATE, text = utterance)
        }
        if (plan.decision == Decision.DICTATE) {
            return AssistantPlan(Decision.REJECT, text = "Диктовка возможна только в открытой заметке.")
        }
        if (plan.decision != Decision.EXECUTE) {
            return plan.copy(text = plan.text.ifBlank { defaultText(plan.decision) })
        }
        if (plan.steps.size > MAX_STEPS) {
            return AssistantPlan(Decision.CLARIFY, text = "Слишком много действий сразу. Назови их по очереди.")
        }
        if (plan.steps.any { NegationGuard.blocks(utterance, it) }) {
            return AssistantPlan(Decision.CLARIFY, text = NEGATED)
        }
        return when (val first = ActionPolicy.resolve(plan.steps.first(), state)) {
            is Verdict.Clarify -> AssistantPlan(Decision.CLARIFY, text = first.text)
            is Verdict.Reject -> AssistantPlan(Decision.REJECT, text = first.text)
            else -> plan
        }
    }

    private fun defaultText(decision: Decision): String =
        if (decision == Decision.CLARIFY) ActionPolicy.WHICH_ACTION else "Действие не выполняется."
}

/**
 * A deterministic safety net for model proposals, not language understanding: if the phrase contains
 * «не», actions with a side effect are not started. Corrections («не картофель, а томат»), stop actions
 * and «не уверен» stay allowed; «не выключай / не переставай» blocks switching the microphone off.
 */
object NegationGuard {
    private val negations = setOf("не", "нельзя")
    private val stopVerbs = listOf("выключ", "отключ", "перестав", "прекращ", "останавлив")
    private val guarded = setOf(
        VoiceAction.SAVE_INSPECTION, VoiceAction.CONFIRM, VoiceAction.EXIT,
        VoiceAction.CAMERA, VoiceAction.OPEN_CAMERA, VoiceAction.CAMERA_NOW,
        VoiceAction.GALLERY, VoiceAction.SYSTEM_GALLERY, VoiceAction.ANALYZE, VoiceAction.COMPARE,
        VoiceAction.NOTE, VoiceAction.CLEAR_NOTE, VoiceAction.JOURNAL, VoiceAction.JOURNAL_NUMBER, VoiceAction.LISTEN_ON,
        VoiceAction.CREATE_PLOT, VoiceAction.RENAME_PLOT, VoiceAction.DELETE_PLOT,
        VoiceAction.SET_SETTING, VoiceAction.SEARCH, VoiceAction.JOURNAL_FILTER, VoiceAction.EXPORT_JOURNAL, VoiceAction.IMPORT_JOURNAL
    )

    fun blocks(utterance: String, step: ActionRequest): Boolean {
        val words = words(utterance)
        if (words.none { it in negations }) {
            return false
        }
        return when (step.action) {
            VoiceAction.ASSESS -> step.value == "correct"
            VoiceAction.LISTEN_OFF -> negatedStop(words)
            else -> step.action in guarded
        }
    }

    private fun negatedStop(words: List<String>): Boolean = words.zipWithNext().any { (first, second) ->
        first in negations && stopVerbs.any { second.startsWith(it) }
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
}
