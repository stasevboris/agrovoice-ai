package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction

/**
 * V-02: the short hands-free dialog of section 6.4 of the 16.10 audit, without wake word and treatment yet.
 *
 * IDLE ─(photo or analysis asked by voice)→ WAITING_RESULT ─(result ready)→ FOLLOW_UP: the app reads the
 * result and asks «Сохранить осмотр?». In FOLLOW_UP «да» saves, «нет» ends, «ряд …» / «участок …» fill
 * slots and keep the question open; [FOLLOW_UP_MS] without an answer, «стоп» or any button ends it.
 * Pure Kotlin: the Activity feeds events and performs the returned prompts.
 */
class FollowUpDialog(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Phase { IDLE, WAITING_RESULT, FOLLOW_UP, SAVING }

    /** What the app should say; listening resumes after speech while the phase is FOLLOW_UP. */
    enum class Prompt { RESULT_AND_QUESTION, SAVED, NOT_SAVED, SAVE_FAILED }

    var phase = Phase.IDLE
        private set
    private var deadline = 0L
    private var listened = false

    /** True while «да» means «сохрани этот осмотр». */
    val awaitingAnswer: Boolean
        get() = phase == Phase.FOLLOW_UP && (!listened || clock() <= deadline)

    /** A photo was taken, chosen or re-checked by voice: the result will be read aloud. */
    fun voiceAnalysisRequested() {
        phase = Phase.WAITING_RESULT
    }

    /** The analysis ended; without a result there is nothing to ask about. */
    fun analysisFinished(hasResult: Boolean): Prompt? {
        if (phase != Phase.WAITING_RESULT) {
            return null
        }
        if (!hasResult) {
            phase = Phase.IDLE
            return null
        }
        open()
        return Prompt.RESULT_AND_QUESTION
    }

    /** A recognised command was handled during the follow-up. */
    fun commandHandled(request: ActionRequest, verdict: Verdict): Prompt? {
        if (phase != Phase.FOLLOW_UP) {
            return null
        }
        // «да» saves; a spoken rating («результат верный») saves the inspection with that rating.
        val saving = verdict is Verdict.Run && verdict.operations.any { it == Operation.SaveInspection || it is Operation.Assess }
        return when {
            saving -> {
                phase = Phase.SAVING
                null
            }
            verdict is Verdict.Skip && request.action == VoiceAction.CONFIRM -> {
                phase = Phase.IDLE
                Prompt.SAVED
            }
            verdict is Verdict.Run && Operation.EndFollowUp in verdict.operations -> {
                phase = Phase.IDLE
                Prompt.NOT_SAVED
            }
            request.action in slotActions -> {
                open()
                null
            }
            else -> {
                // Anything else (camera, journal, note…) leaves the question behind.
                phase = Phase.IDLE
                null
            }
        }
    }

    /** The save started by «да» finished. */
    fun saveFinished(failed: Boolean): Prompt? {
        if (phase != Phase.SAVING) {
            return null
        }
        phase = Phase.IDLE
        return if (failed) Prompt.SAVE_FAILED else Prompt.SAVED
    }

    /**
     * The microphone opened for the answer. The answer window counts from the first opening, not from
     * the result: reading the result aloud must not eat the time to answer.
     */
    fun listeningStarted() {
        if (phase == Phase.FOLLOW_UP && !listened) {
            listened = true
            deadline = clock() + FOLLOW_UP_MS
        }
    }

    /** Called when listening ended; true while the question is still open and listening should resume. */
    fun shouldListenAgain(): Boolean {
        if (phase == Phase.FOLLOW_UP && listened && clock() > deadline) {
            phase = Phase.IDLE
        }
        return phase == Phase.FOLLOW_UP
    }

    /** «стоп», a button, leaving the screen. */
    fun stop() {
        phase = Phase.IDLE
    }

    private fun open() {
        phase = Phase.FOLLOW_UP
        listened = false
        deadline = clock() + FOLLOW_UP_MS
    }

    companion object {
        const val FOLLOW_UP_MS = 15_000L
        const val QUESTION = "Сохранить осмотр? Скажите «да» или «нет». Можно назвать ряд или участок."
        /** Answers that fill the inspection or repeat the result and keep the question open. */
        private val slotActions = setOf(VoiceAction.ROW, VoiceAction.PLOT, VoiceAction.SILENCE, VoiceAction.SPEAK_RESULT)

        fun text(prompt: Prompt): String = when (prompt) {
            Prompt.RESULT_AND_QUESTION -> QUESTION
            Prompt.SAVED -> "Осмотр сохранён."
            Prompt.NOT_SAVED -> "Не сохраняю."
            Prompt.SAVE_FAILED -> "Сохранить не удалось."
        }
    }
}
