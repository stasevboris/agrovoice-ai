package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.journal.FrameRole
import com.agronomy.phytomonitor.journal.MAX_INSPECTION_FRAMES
import com.agronomy.phytomonitor.voice.VoiceAction

object SeriesActions {
    val actions = setOf(VoiceAction.ADD_FRAME, VoiceAction.ADD_FRAME_GALLERY,
        VoiceAction.SELECT_FRAME, VoiceAction.FRAME_ROLE)

    fun resolve(request: ActionRequest, state: AppState): Verdict {
        if (state.hasDraft) return Verdict.Clarify("Сначала восстанови или отмени черновик.")
        val saved = state.screen == Screen.JOURNAL && state.inspectionOpen
        if (state.screen != Screen.MAIN && !saved) return Verdict.Clarify("Сначала открой текущий или сохранённый осмотр.")
        if (request.action == VoiceAction.SELECT_FRAME) {
            val number = request.value.toIntOrNull()
            if (number == null || number !in 1..state.frameCount) return Verdict.Clarify("Назови номер кадра от 1 до ${state.frameCount}.")
            return Verdict.Run(listOf(Operation.SelectFrame(number, saved)))
        }
        if (saved) return Verdict.Clarify("Сохранённые кадры доступны для просмотра. Добавь кадр в текущий осмотр на главном экране.")
        if (!state.hasPhoto) return Verdict.Clarify(ActionPolicy.NEED_PHOTO)
        if (request.action == VoiceAction.FRAME_ROLE) {
            if (FrameRole.entries.none { it.value == request.value }) return Verdict.Reject("Неизвестный ракурс кадра.")
            return Verdict.Run(listOf(Operation.SetFrameRole(request.value)))
        }
        if (!state.hasResult) return Verdict.Clarify(ActionPolicy.NEED_RESULT)
        if (state.frameCount >= MAX_INSPECTION_FRAMES) return Verdict.Clarify("В осмотре уже $MAX_INSPECTION_FRAMES кадров. Начни новый осмотр.")
        return Verdict.Run(listOf(Operation.AddFrame(request.action == VoiceAction.ADD_FRAME_GALLERY,
            request.source != ActionSource.BUTTON)))
    }
}
