package com.agronomy.phytomonitor

enum class AnalysisAssessment {
    NO_DETECTIONS,
    PHOTO_WARNING,
    PRELIMINARY
}

const val NO_DETECTIONS_ADVICE = "При выбранной культуре и пороге лист не найден. " +
    "Это не означает, что растение здорово. Проверь выбранную культуру и сними лист крупнее с другого ракурса."

fun assessAnalysis(detectionCount: Int, quality: PhotoQuality?): AnalysisAssessment = when {
    detectionCount == 0 -> AnalysisAssessment.NO_DETECTIONS
    quality?.advice != null -> AnalysisAssessment.PHOTO_WARNING
    else -> AnalysisAssessment.PRELIMINARY
}

/** The model's most confident leaf belongs to another crop than the one chosen by hand. */
fun otherCropWarning(chosen: String, other: String): String =
    "Модель считает лист скорее культурой «$other», чем выбранной «$chosen». " +
        "Результат по выбранной культуре может быть неверным: проверьте выбор культуры или переснимите."

/** Shared wording for TTS and visible photo warnings; confidence is never a diagnosis probability. */
fun spokenAnalysis(labels: List<String>, quality: PhotoQuality?): String {
    val warning = quality?.advice?.let { "$it " }.orEmpty()
    if (labels.isEmpty()) return warning + NO_DETECTIONS_ADVICE
    val leaves = labels.take(5).mapIndexed { index, label -> "Лист ${index + 1}. $label." }
    return warning + "Предварительный результат. " + leaves.joinToString(" ") +
        " Результат нужно проверить."
}
