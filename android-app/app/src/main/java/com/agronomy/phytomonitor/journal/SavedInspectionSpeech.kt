package com.agronomy.phytomonitor.journal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads persisted labels, never the current detector or its current class order. */
object SavedInspectionSpeech {
    fun text(details: InspectionWithDetails): String {
        val inspection = details.inspection
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))
            .format(Date(inspection.createdAt))
        val labels = details.photos.firstOrNull()?.detections.orEmpty()
            .sortedByDescending { it.confidence }
        return buildList {
            add("Сохранённый осмотр от $date. Предварительный результат модели ${inspection.modelVersion}.")
            if (labels.isEmpty()) add("В сохранённом результате листьев выше порога не найдено.")
            labels.forEachIndexed { index, detection -> add("Лист ${index + 1}: ${detection.labelRu}.") }
            add("Результат модели не подтверждает диагноз.")
            details.plot?.name?.let { add("Участок: $it.") }
            inspection.row?.let { add("Ряд: $it.") }
            if (inspection.note.isNotBlank()) add("Заметка: ${inspection.note}")
        }.joinToString(" ")
    }
}
