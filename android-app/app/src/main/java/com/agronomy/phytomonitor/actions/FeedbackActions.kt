package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import org.json.JSONObject

object FeedbackActions {
    val actions = setOf(VoiceAction.FRAME_FEEDBACK, VoiceAction.SELECT_EXPORT_FRAME, VoiceAction.EXPORT_FEEDBACK)

    fun resolve(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.JOURNAL || !state.inspectionOpen) return Verdict.Clarify("Открой сохранённый осмотр и выбери кадр.")
        return when (request.action) {
            VoiceAction.EXPORT_FEEDBACK -> if (state.feedbackExportCount == 0) Verdict.Clarify("Отметь кадры для экспорта.") else Verdict.Run(listOf(Operation.PrepareFeedbackExport),
                notice = "В экспорт войдут выбранные фото, технический результат и ваша оценка. Заметки, GPS и EXIF исключены. На изображении и в исправлении могут быть личные сведения. Проверьте их. Подтверди сохранение или скажи отмена.")
            VoiceAction.SELECT_EXPORT_FRAME -> {
                val number = request.value.toIntOrNull()
                if (number == null || number !in 1..state.frameCount) Verdict.Reject("Укажи номер кадра.")
                else Verdict.Run(listOf(Operation.ToggleExportFrame(number)))
            }
            else -> {
                val data = runCatching { JSONObject(request.value) }.getOrNull()
                val verdict = data?.optString("verdict")
                val correction = data?.optString("correction").orEmpty()
                if (verdict !in setOf("correct", "incorrect", "unknown") || correction.length > 500 || verdict != "incorrect" && correction.isNotEmpty()) {
                    Verdict.Reject("Укажи оценку кадра и исправление до 500 символов только для неверного результата.")
                } else Verdict.Run(listOf(Operation.SaveFrameFeedback(verdict!!, correction)))
            }
        }
    }

    fun value(verdict: String, correction: String = "") = JSONObject().put("verdict", verdict).put("correction", correction).toString()

    fun parse(text: String): VoiceCommand? {
        val phrase = text.trim().lowercase().replace('ё', 'е')
        val verdict = when (phrase) {
            "кадр верный", "результат кадра верный" -> "correct"
            "кадр неверный", "результат кадра неверный" -> "incorrect"
            "не знаю", "не знаю результат кадра" -> "unknown"
            else -> null
        }
        if (verdict != null) return VoiceCommand(VoiceAction.FRAME_FEEDBACK, "Оценка кадра", value(verdict))
        if (phrase.startsWith("исправление кадра ")) return VoiceCommand(VoiceAction.FRAME_FEEDBACK, "Исправление кадра", value("incorrect", phrase.removePrefix("исправление кадра ")))
        if (phrase == "экспорт выбранных кадров") return VoiceCommand(VoiceAction.EXPORT_FEEDBACK, "Экспорт выбранных кадров")
        val number = phrase.removePrefix("выбери для экспорта кадр ").takeIf { phrase.startsWith("выбери для экспорта кадр ") }
            ?.let(com.agronomy.phytomonitor.voice.RussianPhotoNumber::parse)
        return number?.let { VoiceCommand(VoiceAction.SELECT_EXPORT_FRAME, "Выбор кадра для экспорта", it.toString()) }
    }
}
