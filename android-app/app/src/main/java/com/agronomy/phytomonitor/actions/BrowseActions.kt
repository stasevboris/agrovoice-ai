package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand

/** Small, explicit settings and journal operations; system file pickers remain Android UI. */
object BrowseActions {
    val actions = setOf(VoiceAction.SET_SETTING, VoiceAction.SEARCH, VoiceAction.JOURNAL_FILTER,
        VoiceAction.EXPORT_JOURNAL, VoiceAction.IMPORT_JOURNAL)
    val settingNames = mapOf("voice_enabled" to "голосовое управление",
        "spoken_follow_up" to "озвучку после фото", "free_speech" to "свободную речь")
    val filterNames = mapOf("all" to "все осмотры", "correct" to "верные результаты",
        "incorrect" to "ошибки модели", "uncertain" to "неуверенные результаты", "unrated" to "осмотры без оценки")

    fun parse(text: String, screen: String): VoiceCommand? {
        val phrase = text.lowercase().replace('ё', 'е').trim().removeSuffix(".")
        if (screen == "settings") {
            for ((key, name) in settingNames) {
                for ((verb, enabled) in mapOf("включи" to true, "выключи" to false, "отключи" to false)) {
                    if (phrase == "$verb $name") return command(VoiceAction.SET_SETTING, "$key:$enabled")
                }
            }
        }
        if (screen in setOf("journal", "knowledge")) {
            if (phrase in setOf("сбрось поиск", "очисти поиск")) return command(VoiceAction.SEARCH)
            for (prefix in listOf("найди ", "поиск ", "ищи ")) {
                if (phrase.startsWith(prefix)) return command(VoiceAction.SEARCH, phrase.removePrefix(prefix))
            }
        }
        if (screen == "journal") {
            filterNames.entries.firstOrNull { phrase == "покажи ${it.value}" }?.let {
                return command(VoiceAction.JOURNAL_FILTER, it.key)
            }
            return when (phrase) {
                "создай резервную копию", "сохрани архив", "экспортируй журнал" -> command(VoiceAction.EXPORT_JOURNAL, "archive")
                "экспортируй таблицу" -> command(VoiceAction.EXPORT_JOURNAL, "csv")
                "экспортируй json" -> command(VoiceAction.EXPORT_JOURNAL, "json")
                "восстанови журнал", "импортируй журнал" -> command(VoiceAction.IMPORT_JOURNAL)
                else -> null
            }
        }
        return null
    }

    fun resolve(request: ActionRequest, state: AppState): Verdict {
        val action = request.action
        if (action == VoiceAction.SET_SETTING) {
            if (state.screen != Screen.SETTINGS) return Verdict.Clarify("Сначала открой настройки приложения.")
            val parts = request.value.split(":")
            if (parts.size != 2 || parts[0] !in settingNames || parts[1] !in setOf("true", "false")) {
                return Verdict.Reject("Неизвестная настройка или значение.")
            }
            val enabled = parts[1] == "true"
            if (state.settings[parts[0]] == enabled) return Verdict.Skip("Эта настройка уже установлена.")
            return Verdict.Run(listOf(Operation.SetSetting(parts[0], enabled)))
        }
        if (action == VoiceAction.SEARCH) {
            if (state.screen !in setOf(Screen.JOURNAL, Screen.KNOWLEDGE)) return Verdict.Clarify("Поиск доступен в журнале и справочнике.")
            if (request.value.length > 300) return Verdict.Reject("Сократи поисковый запрос до 300 символов.")
            return Verdict.Run(listOf(Operation.Search(state.screen,
                if (request.source == ActionSource.BUTTON) request.value else request.value.trim())))
        }
        if (state.screen != Screen.JOURNAL || state.inspectionOpen) return Verdict.Clarify("Сначала вернись к списку осмотров.")
        return when (action) {
            VoiceAction.JOURNAL_FILTER -> if (request.value in filterNames) Verdict.Run(listOf(Operation.FilterJournal(request.value)))
                else Verdict.Reject("Неизвестный фильтр журнала.")
            VoiceAction.EXPORT_JOURNAL -> when {
                request.value !in setOf("archive", "json", "csv") -> Verdict.Reject("Неизвестный формат журнала.")
                state.journalCount == 0 -> Verdict.Clarify("В журнале пока нет осмотров для экспорта.")
                else -> Verdict.Run(listOf(Operation.ExportJournal(request.value)))
            }
            else -> Verdict.Run(listOf(Operation.ImportJournal))
        }
    }

    private fun command(action: VoiceAction, value: String = "") = VoiceCommand(action, "", value)
}
