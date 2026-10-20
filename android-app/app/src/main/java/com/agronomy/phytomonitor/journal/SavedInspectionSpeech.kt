package com.agronomy.phytomonitor.journal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.agronomy.phytomonitor.label

/** Reads persisted labels, never the current detector or its current class order. */
object SavedInspectionSpeech {
    fun text(details: InspectionWithDetails, selectedFrame: Int = 1): String {
        val inspection = details.inspection
        val frame = details.photos.getOrNull(selectedFrame - 1)
        val report = frame?.photo?.predictionJson?.let { org.json.JSONObject(it) }
        val model = report?.optString("model") ?: inspection.modelVersion.takeIf { details.photos.size <= 1 }
            ?: "не указанной в сохранённых данных"
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))
            .format(Date(inspection.createdAt))
        val labels = frame?.detections.orEmpty()
            .sortedByDescending { it.confidence }
        return buildList {
            add("Сохранённый осмотр от $date. Кадр $selectedFrame. Предварительный результат модели $model.")
            if (frame != null) add(FrameRole.from(frame.photo.frameRole).label + ".")
            if (labels.isEmpty()) add("В сохранённом результате листьев выше порога не найдено.")
            labels.forEachIndexed { index, detection -> add("Лист ${index + 1}: ${detection.labelRu}.") }
            add("Результат модели не подтверждает диагноз.")
            frame?.feedback?.let {
                val opinion = when (it.verdict) { "correct" -> "верно"; "incorrect" -> "неверно"; else -> "не знаю" }
                add("Ваша оценка кадра: $opinion. Это мнение пользователя, без проверки специалистом.")
                if (it.correction.isNotBlank()) add("Ваше исправление: ${it.correction}.")
            }
            details.plot?.name?.let { add("Участок: $it.") }
            inspection.row?.let { add("Ряд: $it.") }
            frame?.plantName?.let { add("Растение: $it.") }
            com.agronomy.phytomonitor.seriesClarification(details.photos.map { it.photo.predictionJson })?.let { add(it) }
            if (inspection.note.isNotBlank()) add("Заметка: ${inspection.note}")
        }.joinToString(" ")
    }
}
