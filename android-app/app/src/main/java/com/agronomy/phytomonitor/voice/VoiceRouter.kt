package com.agronomy.phytomonitor.voice

import com.agronomy.phytomonitor.actions.ActionDispatcher
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AssistantPlan
import com.agronomy.phytomonitor.actions.FollowUpDialog
import com.agronomy.phytomonitor.actions.Operation
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.actions.Verdict

/** Reads the current navigation destination for every phrase. */
class VoiceRouter(
    private val screenProvider: () -> Screen,
    private val foreground: () -> Boolean,
    private val actions: ActionDispatcher,
    private val followUp: FollowUpDialog,
    private val prompt: (FollowUpDialog.Prompt) -> Unit,
    private val phaseChanged: (FollowUpDialog.Phase) -> Unit,
    private val followUpEnabled: () -> Boolean = { true }
) {
    val screen: Screen get() = screenProvider()

    fun command(command: VoiceCommand): Verdict? {
        if (!foreground()) return null
        val request = ActionRequest(command.action, command.value, ActionSource.VOICE)
        val verdict = actions.dispatch(request)
        val leavingInspection = verdict is Verdict.Run && verdict.operations.any {
            it == Operation.OpenJournal || it == Operation.OpenPlots || it == Operation.OpenSettings || it is Operation.OpenKnowledge
        }
        if (leavingInspection) {
            followUp.stop()
        } else if (followUp.phase == FollowUpDialog.Phase.FOLLOW_UP) {
            followUp.commandHandled(request, verdict)?.let(prompt)
        } else if (request.action in stopActions) {
            followUp.stop()
        } else if (followUpEnabled() && verdict is Verdict.Run && verdict.operations.any { it in startsAnalysis || it is Operation.OpenCamera }) {
            followUp.voiceAnalysisRequested()
        }
        phaseChanged(followUp.phase)
        return verdict
    }

    fun plan(commands: List<VoiceCommand>) {
        if (!foreground()) return
        followUp.stop()
        phaseChanged(followUp.phase)
        actions.submitPlan(commands.map { ActionRequest(it.action, it.value, ActionSource.VOICE) })
    }

    fun answer(json: String, utterance: String): AssistantPlan? =
        if (foreground()) actions.submit(json, utterance) else null

    private companion object {
        val startsAnalysis = setOf(Operation.TakeShot, Operation.ConfirmGallery, Operation.Analyze)
        val stopActions = setOf(VoiceAction.CANCEL, VoiceAction.BACK, VoiceAction.LISTEN_OFF, VoiceAction.EXIT)
    }
}
