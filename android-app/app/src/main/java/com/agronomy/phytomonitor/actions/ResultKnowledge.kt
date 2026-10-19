package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction

object ResultKnowledge {
    fun resolve(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.MAIN && !(state.screen == Screen.JOURNAL && state.inspectionOpen)) {
            return Verdict.Clarify("Сначала открой результат осмотра или справочник.")
        }
        if (state.resultKnowledgeCodes.isEmpty()) return Verdict.Clarify("Нет листа с определённым состоянием. Сделай другой снимок или открой справочник вручную.")
        val index = if (request.action == VoiceAction.LEAF_KNOWLEDGE) (request.value.toIntOrNull() ?: 0) - 1 else {
            if (state.resultKnowledgeCodes.size != 1) return Verdict.Clarify("В результате несколько листьев. Скажи «справка по листу» и его номер.")
            0
        }
        if (index !in state.resultKnowledgeCodes.indices) return Verdict.Clarify("Такого номера листа в открытом результате нет.")
        val code = state.resultKnowledgeCodes[index]
            ?: return Verdict.Clarify("Состояние этого листа не определено или для него нет карточки. Выбери культуру в справочнике вручную.")
        return Verdict.Run(listOf(Operation.OpenResultKnowledge(code, request.source != ActionSource.BUTTON)))
    }
}
