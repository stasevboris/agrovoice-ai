package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONObject

data class PlotTarget(val id: String, val name: String)

/** Names are resolved once to an ID; deletion is checked again when confirmed. */
object PlotActions {
    val actions = setOf(VoiceAction.CREATE_PLOT, VoiceAction.RENAME_PLOT, VoiceAction.DELETE_PLOT)

    fun renameValue(from: String, to: String): String = JSONObject().put("from", from).put("to", to).toString()

    fun parse(phrase: String, names: Collection<String>): VoiceCommand? {
        val text = phrase.trim().lowercase().replace('ё', 'е').removeSuffix(".")
        for (prefix in listOf("создай участок ", "добавь участок ", "новый участок ")) {
            if (text.startsWith(prefix)) return VoiceCommand(VoiceAction.CREATE_PLOT, "Создать участок", text.removePrefix(prefix))
        }
        for (prefix in listOf("удали участок ", "удалить участок ")) {
            if (text.startsWith(prefix)) return VoiceCommand(VoiceAction.DELETE_PLOT, "Удаление требует подтверждения", text.removePrefix(prefix))
        }
        val body = text.removePrefix("переименуй участок ")
        if (body == text) return null
        val matches = names.filter { body.startsWith(VoiceCommands.spokenPlot(it) + " в ") }
        if (matches.size != 1) return null
        val from = matches.single()
        val to = body.removePrefix(VoiceCommands.spokenPlot(from) + " в ")
        return VoiceCommand(VoiceAction.RENAME_PLOT, "Переименовать участок", renameValue(from, to))
    }

    fun resolve(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.PLOTS) return Verdict.Clarify("Сначала открой участки.")
        val from: String
        val name: String
        if (request.action == VoiceAction.RENAME_PLOT) {
            val pair = runCatching {
                val json = JSONObject(request.value)
                json.getString("from") to json.getString("to")
            }.getOrNull() ?: return Verdict.Reject("Укажи прежнее и новое название участка.")
            from = pair.first
            name = clean(pair.second)
        } else {
            from = request.value
            name = clean(request.value)
        }
        if (name.isBlank() || name.length > 120 || name.any { it.isISOControl() }) {
            return Verdict.Clarify("Название участка должно содержать от 1 до 120 символов.")
        }
        val target = state.plotTargets.filter { VoiceCommands.spokenPlot(it.name) == VoiceCommands.spokenPlot(from) }
        if (request.action != VoiceAction.CREATE_PLOT && target.size != 1) {
            return Verdict.Clarify("Участок не найден или название неоднозначно. Уточни название из списка.")
        }
        if (request.action != VoiceAction.DELETE_PLOT && state.plotTargets.any {
                it.name.equals(name, ignoreCase = true) && (request.action == VoiceAction.CREATE_PLOT || it.id != target.single().id)
            }) return Verdict.Clarify("Участок «$name» уже есть.")
        val operation = when (request.action) {
            VoiceAction.CREATE_PLOT -> Operation.CreatePlot(name)
            VoiceAction.RENAME_PLOT -> Operation.RenamePlot(target.single().id, name)
            else -> Operation.AskDeletePlot(target.single())
        }
        return Verdict.Run(listOf(operation))
    }

    fun confirm(state: AppState): Verdict {
        val pending = state.pendingPlotDeletion ?: return Verdict.Clarify(ActionPolicy.WHICH_ACTION)
        if (state.screen != Screen.PLOTS || pending !in state.plotTargets) {
            return Verdict.Run(listOf(Operation.CancelPlotDeletion), "Участок изменился. Выбери его заново.", true)
        }
        return Verdict.Run(listOf(Operation.DeletePlot(pending)))
    }

    private fun clean(name: String) = name.trim().replace(Regex("\\s+"), " ")
}
