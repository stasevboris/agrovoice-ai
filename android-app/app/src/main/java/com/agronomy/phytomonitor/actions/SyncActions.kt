package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction

data class SyncTarget(val id: String, val updatedAt: Long)

sealed interface SyncConfirmation {
    val generation: String
    data class All(override val generation: String, val targets: List<SyncTarget>) : SyncConfirmation
    data class Conflict(override val generation: String, val target: SyncTarget, val serverJson: String) : SyncConfirmation
}

object SyncActions {
    val actions = setOf(VoiceAction.SYNC_SETTINGS, VoiceAction.SYNC_STATUS, VoiceAction.SYNC_CURRENT,
        VoiceAction.SYNC_RETRY, VoiceAction.SYNC_RECEIVE, VoiceAction.SYNC_LOGOUT, VoiceAction.SYNC_ALL, VoiceAction.SYNC_CONFLICT)

    fun resolve(request: ActionRequest, state: AppState): Verdict? {
        val pending = state.pendingSync
        if (pending != null && request.action in setOf(VoiceAction.CANCEL, VoiceAction.BACK)) {
            return run(Operation.CancelSyncConfirmation)
        }
        if (pending != null && request.action == VoiceAction.CONFIRM) {
            if (state.syncGeneration != pending.generation) return Verdict.Clarify("Аккаунт изменился. Запросите подтверждение заново.")
            if (pending is SyncConfirmation.All && state.screen != Screen.SETTINGS ||
                pending is SyncConfirmation.Conflict && (!state.inspectionOpen || state.openedInspectionId != pending.target.id)) {
                return Verdict.Clarify("Контекст осмотра изменился. Запросите подтверждение заново.")
            }
            if (state.busy) return Verdict.Clarify("Подождите завершения текущего действия синхронизации.")
            return run(Operation.ConfirmSync(pending))
        }
        if (request.action !in actions) return null
        if (request.action == VoiceAction.SYNC_SETTINGS) return run(Operation.OpenSyncSettings)
        if (request.action == VoiceAction.SYNC_STATUS) return run(Operation.SpeakSyncStatus)
        if (request.action == VoiceAction.SYNC_LOGOUT) return run(Operation.LogoutSync)
        if (state.syncGeneration == null) return Verdict.Clarify("Сначала войдите на сервер в настройках синхронизации. Осмотры сохранены на устройстве.")
        if (state.busy) return Verdict.Clarify("Подождите завершения текущего действия синхронизации.")
        if (pending != null) return Verdict.Clarify("Сначала подтвердите передачу или скажите «отмена».")
        return when (request.action) {
            VoiceAction.SYNC_CURRENT, VoiceAction.SYNC_CONFLICT -> {
                val id = state.openedInspectionId?.takeIf { state.screen == Screen.JOURNAL && state.inspectionOpen }
                    ?: return Verdict.Clarify("Откройте сохранённый осмотр в журнале.")
                run(if (request.action == VoiceAction.SYNC_CURRENT) Operation.SendInspection(id) else Operation.AskSyncConflict(id))
            }
            VoiceAction.SYNC_ALL -> if (state.screen == Screen.SETTINGS) run(Operation.AskSyncAll)
                else Verdict.Clarify("Отправка всего журнала доступна в настройках синхронизации.")
            VoiceAction.SYNC_RETRY -> run(Operation.RetrySync)
            VoiceAction.SYNC_RECEIVE -> run(Operation.ReceiveSync)
            else -> null
        }
    }

    private fun run(operation: Operation) = Verdict.Run(listOf(operation))
}
