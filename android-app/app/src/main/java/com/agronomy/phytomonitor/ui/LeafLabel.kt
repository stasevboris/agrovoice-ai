package com.agronomy.phytomonitor.ui

/** How a result row is coloured. It reflects the class name only, not how sure the model is. */
enum class LeafState { HEALTHY, ATTENTION, UNKNOWN }

/** A Russian class label such as «Томат: фитофтороз» split into crop and condition. */
data class LeafLabel(val crop: String, val condition: String, val state: LeafState)

private val healthyMarkers = listOf("без указанной болезни", "здоров")
private val unknownMarkers = listOf("состояние неизвестно")

fun parseLeafLabel(label: String): LeafLabel {
    val separator = label.indexOf(':')
    if (separator < 0) {
        return LeafLabel(label.trim(), "", LeafState.UNKNOWN)
    }
    val crop = label.substring(0, separator).trim()
    val condition = label.substring(separator + 1).trim()
    val lower = condition.lowercase()
    val state = when {
        unknownMarkers.any { lower.contains(it) } -> LeafState.UNKNOWN
        healthyMarkers.any { lower.contains(it) } -> LeafState.HEALTHY
        lower == "лист" -> LeafState.HEALTHY
        else -> LeafState.ATTENTION
    }
    return LeafLabel(crop, condition.replaceFirstChar { it.uppercase() }, state)
}
