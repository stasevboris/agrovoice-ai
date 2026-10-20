package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.journal.FrameRole
import org.json.JSONObject

val FrameRole.label: String get() = when (this) {
    FrameRole.UNSPECIFIED -> "Ракурс не указан"
    FrameRole.OVERVIEW -> "Общий вид"
    FrameRole.FRONT -> "Лицевая сторона"
    FrameRole.BACK -> "Оборот листа"
}

/** A prompt to clarify the subject, never a combined diagnosis or averaged confidence. */
fun seriesClarification(reports: List<String?>): String? {
    val classes = reports.mapNotNull { raw -> raw?.let {
        val detections = JSONObject(it).optJSONArray("detections") ?: return@let null
        (0 until detections.length()).map { index ->
            detections.getJSONObject(index).optString("class_name")
        }.toSet()
    } }
    return if (classes.distinct().size > 1) {
        "Результаты кадров различаются. Уточни: на всех кадрах одно растение? Проверь ракурсы и результаты каждого кадра отдельно. Общий диагноз серии не определён."
    } else null
}
