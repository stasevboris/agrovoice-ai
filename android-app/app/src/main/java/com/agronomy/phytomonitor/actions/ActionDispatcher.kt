package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction

/** What the dispatcher needs from the app: the current state, a way to act and a way to answer. */
interface ActionPort {
    fun state(): AppState
    fun perform(operation: Operation)
    fun tell(text: String, isError: Boolean, request: ActionRequest?)
}

/**
 * The one executor for buttons, fixed voice phrases and assistant plans.
 *
 * Every request is checked by [ActionPolicy] against the state at the moment it runs. A plan of several
 * steps waits while the app is busy and continues only after [settled] reports success; a new request,
 * «отмена» or leaving the screen drops the rest of the plan. A repeated recognition result within
 * [REPEAT_WINDOW_MS] is ignored; saving is also protected by the state, so no timing makes it twice.
 */
class ActionDispatcher(
    private val port: ActionPort,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val plan = ArrayDeque<ActionRequest>()
    private var waiting = false
    private var lastRequest: ActionRequest? = null
    private var lastRequestAt = 0L

    /** Steps of the current plan that have not started yet. */
    val remainingSteps: Int get() = plan.size

    /** A button press or a recognized phrase. It replaces any unfinished plan. */
    fun dispatch(request: ActionRequest): Verdict {
        if (isRepeat(request)) {
            return Verdict.Repeated
        }
        cancelPlan()
        return execute(request)
    }

    /** Several steps from one free phrase; each step is checked when its turn comes. */
    fun submitPlan(steps: List<ActionRequest>) {
        cancelPlan()
        plan.addAll(steps)
        runNext()
    }

    /** A router answer in the assistant JSON protocol for the phrase [utterance]. */
    fun submit(json: String, utterance: String): AssistantPlan {
        val reviewed = AssistantProtocol.read(json, port.state(), utterance)
        cancelPlan()
        when (reviewed.decision) {
            Decision.EXECUTE -> {
                plan.addAll(reviewed.steps)
                runNext()
            }
            Decision.CLARIFY -> port.tell(reviewed.text, isError = true, request = null)
            Decision.REJECT -> port.tell(reviewed.text, isError = true, request = null)
            Decision.DICTATE -> Unit
        }
        return reviewed
    }

    /** Called when background work has finished. A failed step drops the rest of the plan. */
    fun settled(failed: Boolean) {
        if (!waiting) {
            return
        }
        waiting = false
        if (failed) {
            plan.clear()
            return
        }
        runNext()
    }

    fun cancelPlan() {
        plan.clear()
        waiting = false
    }

    private fun runNext() {
        while (!waiting && plan.isNotEmpty()) {
            val verdict = execute(plan.removeFirst())
            val succeeded = verdict is Verdict.Run || verdict is Verdict.Skip
            if (!succeeded) {
                plan.clear()
            }
        }
    }

    private fun execute(request: ActionRequest): Verdict {
        val verdict = ActionPolicy.resolve(request, port.state())
        when (verdict) {
            is Verdict.Run -> {
                verdict.operations.forEach(port::perform)
                verdict.notice?.let { port.tell(it, verdict.noticeIsError, request) }
                waiting = port.state().busy
            }
            is Verdict.Clarify -> port.tell(verdict.text, isError = true, request = request)
            is Verdict.Reject -> port.tell(verdict.text, isError = true, request = request)
            is Verdict.Skip -> port.tell(verdict.text, isError = false, request = request)
            Verdict.Dictate, Verdict.Repeated -> Unit
        }
        return verdict
    }

    /** The same recognized result again: Vosk may deliver a final twice, a user may repeat a phrase. */
    private fun isRepeat(request: ActionRequest): Boolean {
        val now = clock()
        val previous = lastRequest
        val repeated = request.source != ActionSource.BUTTON &&
            request.action !in repeatableActions &&
            previous != null &&
            previous.action == request.action &&
            previous.value == request.value &&
            now - lastRequestAt < REPEAT_WINDOW_MS
        if (!repeated) {
            lastRequest = request
            lastRequestAt = now
        }
        return repeated
    }

    companion object {
        const val REPEAT_WINDOW_MS = 1500L

        /** Navigation is meant to be repeated: «дальше, дальше», «назад, назад». */
        val repeatableActions = setOf(
            VoiceAction.NEXT_PHOTO, VoiceAction.PREVIOUS_PHOTO, VoiceAction.SCROLL,
            VoiceAction.THRESHOLD_UP, VoiceAction.THRESHOLD_DOWN, VoiceAction.BACK, VoiceAction.CANCEL
        )
    }
}
