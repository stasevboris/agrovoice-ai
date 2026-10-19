package com.agronomy.phytomonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

internal data class SavedProvenanceInfo(val taxonomy: String, val details: List<String>)

/** Reads only the saved JSON; replacing models must not relabel a historical result. */
internal fun savedProvenanceInfo(predictionJson: String): SavedProvenanceInfo {
    val report = runCatching { JSONObject(predictionJson) }.getOrNull() ?: JSONObject()
    val provenance = report.optJSONObject("result_provenance")
    val taxonomy = provenance.savedText("taxonomy_version")?.takeIf { it != "unspecified" }
        ?: "не указана в сохранённом отчёте"
    val model = provenance.savedText("model_id") ?: report.savedText("model") ?: "не указана"
    val hash = provenance.savedText("model_sha256") ?: report.savedText("model_sha256") ?: "не указан"
    val details = mutableListOf("Модель: $model", "SHA-256 модели: $hash")
    if (provenance == null) {
        details += "Полный состав классов и параметры обработки в этом старом отчёте не сохранены."
    } else {
        val classes = provenance.optJSONArray("classes")?.takeIf { array ->
            array.length() > 0 && (0 until array.length()).all { index ->
                (array.opt(index) as? String)?.isNotBlank() == true
            }
        }
        details += "Классов: ${classes?.length() ?: "не указано"}"
        details += "SHA-256 порядка классов: ${provenance.savedText("class_order_sha256") ?: "не указан"}"
        val preprocessing = provenance.optJSONObject("preprocessing")
        val side = (preprocessing?.opt("input_side") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in 1.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
        details += if (side == null) "Вход модели: не указан." else
            "Вход модели: $side × $side, ${preprocessing.savedText("channels") ?: "каналы не указаны"}."
        val postprocessing = provenance.optJSONObject("postprocessing")
        val threshold = postprocessing.savedUnitValue("confidence_threshold") ?: "не указан"
        details += "Порог отбора: $threshold; фильтр культуры: ${postprocessing.savedText("crop_filter") ?: "не указан"}."
        val nms = postprocessing?.optJSONObject("nms")
        details += when (nms?.opt("enabled") as? Boolean) {
            true -> "Объединение рамок: NMS, IoU ${nms.savedUnitValue("iou_threshold") ?: "неизвестен"}."
            false -> "Дополнительное NMS в приложении не применялось."
            null -> "Применение NMS: неизвестно."
        }
        provenance.optJSONObject("classifier")?.let {
            details += "Классификатор: ${it.savedText("id") ?: "не указан"}; SHA-256: ${it.savedText("sha256") ?: "не указан"}."
        }
    }
    return SavedProvenanceInfo(taxonomy, details)
}

private fun JSONObject?.savedText(key: String): String? = (this?.opt(key) as? String)
    ?.takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject?.savedUnitValue(key: String): String? = (this?.opt(key) as? Number)?.toDouble()
    ?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { "%.2f".format(Locale.ROOT, it) }

@Composable
internal fun ResultProvenance(predictionJson: String) {
    val info = remember(predictionJson) { savedProvenanceInfo(predictionJson) }
    var expanded by remember(predictionJson) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Версия таксономии: ${info.taxonomy}.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Скрыть сведения о модели" else "Сведения об исходной модели")
        }
        if (expanded) {
            info.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
