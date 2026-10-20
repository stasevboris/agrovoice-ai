package com.agronomy.phytomonitor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.agronomy.phytomonitor.journal.FrameRole
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.journal.InspectionWithDetails
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.ui.EmptyPhoto
import com.agronomy.phytomonitor.ui.LeafResultRow
import com.agronomy.phytomonitor.ui.MessageBanner
import com.agronomy.phytomonitor.ui.PhotoWithDetections
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A saved inspection exactly as it was saved: no new analysis, other model or threshold. */
data class SavedInspectionView(
    val details: InspectionWithDetails,
    val photo: Bitmap?,
    val boxes: List<LeafDetection>,
    val labels: List<String>,
    val selectedFrame: Int = 1
) {
    val activePhoto get() = details.photos.getOrNull(selectedFrame - 1)
    val predictionJson: String? get() = activePhoto?.photo?.predictionJson
        ?: details.inspection.predictionJson.takeIf { details.photos.size == 1 }
    companion object {
        /** Frames come from the saved detections, strongest first, in the decoded-photo coordinates of the report. */
        fun of(details: InspectionWithDetails, photo: Bitmap?, selectedFrame: Int = 1): SavedInspectionView {
            val detections = details.photos.getOrNull(selectedFrame - 1)?.detections.orEmpty().sortedByDescending { it.confidence }
            val boxes = detections.mapIndexed { index, item ->
                LeafDetection(index, item.confidence.toFloat(), item.x1.toFloat(), item.y1.toFloat(), item.x2.toFloat(), item.y2.toFloat())
            }
            return SavedInspectionView(details, photo, boxes, detections.map { it.labelRu }, selectedFrame)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedInspectionScreen(view: SavedInspectionView, onBack: () -> Unit, onRead: () -> Unit,
    onEditNote: () -> Unit, onKnowledge: (Int) -> Unit, onReanalyse: () -> Unit,
    syncModel: com.agronomy.phytomonitor.sync.SyncViewModel,
    onSync: (com.agronomy.phytomonitor.voice.VoiceAction) -> Unit,
    onPlantHistory: () -> Unit = {}, onSelectFrame: (Int) -> Unit = {},
    exportFrames: Set<String> = emptySet(), onToggleExport: (Int) -> Unit = {},
    onExportFeedback: () -> Unit = {}, onFeedback: (String, String) -> Unit = { _, _ -> }) {
    val inspection = view.details.inspection
    val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(inspection.createdAt))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AgroIcons.Back, contentDescription = "Вернуться к журналу") }
            Text("Осмотр $date", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                view.details.photos.forEachIndexed { index, frame ->
                    FilterChip(selected = view.selectedFrame == index + 1,
                        onClick = { onSelectFrame(index + 1) },
                        label = { Text("Кадр ${index + 1}: ${FrameRole.from(frame.photo.frameRole).label}") })
                }
            }
            seriesClarification(view.details.photos.map { it.photo.predictionJson })?.let {
                MessageBanner(it, isError = false)
            }
            val photo = view.photo
            if (photo == null) {
                EmptyPhoto()
                MessageBanner("Фото этого осмотра не найдено на телефоне. Восстановите журнал из архива с фото.", isError = true)
            } else {
                PhotoWithDetections(photo, view.boxes, showBoxes = true)
            }
            Text(
                "Кадр ${view.selectedFrame}. Уверенность не означает вероятность болезни.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (view.labels.isEmpty()) {
                Text(if (view.predictionJson == null) "Полный анализ этого кадра не сохранён."
                    else "Модель не нашла листьев выше порога.", style = MaterialTheme.typography.bodyMedium)
            }
            view.labels.forEachIndexed { index, label ->
                LeafResultRow(number = index + 1, label = label, confidence = view.boxes[index].confidence, prominent = index == 0)
                OutlinedButton(onClick = { onKnowledge(index + 1) }) { Text("Справка по листу ${index + 1}") }
            }
            SavedFacts(view)
            val feedback = view.activePhoto?.feedback
            var correction by rememberSaveable(view.activePhoto?.photo?.id, feedback?.revision) { mutableStateOf(feedback?.correction.orEmpty()) }
            Text("Ваша оценка выбранного кадра", style = MaterialTheme.typography.titleMedium)
            Text("Это мнение пользователя, без проверки специалистом. Исходный ответ модели сохраняется.")
            feedback?.let { Text("Сохранено: ${if (it.verdict == "unknown") "не знаю" else reviewText(it.verdict)}${if (it.correction.isNotBlank()) ": ${it.correction}" else ""}") }
            androidx.compose.material3.OutlinedTextField(value = correction,
                onValueChange = { correction = it.take(500) }, label = { Text("Ваше исправление (необязательно)") },
                modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFeedback("correct", "") }) { Text("Верно") }
                OutlinedButton(onClick = { onFeedback("incorrect", correction) }) { Text("Неверно / сохранить исправление") }
                OutlinedButton(onClick = { onFeedback("unknown", "") }) { Text("Не знаю") }
            }
            Text("Кадры для экспорта", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                view.details.photos.forEachIndexed { index, frame ->
                    FilterChip(selected = frame.photo.id in exportFrames, onClick = { onToggleExport(index + 1) },
                        label = { Text("Экспорт: кадр ${index + 1}") })
                }
            }
            OutlinedButton(onClick = onExportFeedback, enabled = exportFrames.isNotEmpty()) { Text("Экспорт выбранных кадров") }
            view.predictionJson?.let { ResultProvenance(it) }
            com.agronomy.phytomonitor.sync.InspectionSyncPanel(syncModel, inspection.id, inspection.updatedAt, onSync)
            if (view.details.photos.any { it.photo.plantId != null }) {
                OutlinedButton(onClick = onPlantHistory) { Text("История растения") }
            }
            OutlinedButton(onClick = onEditNote, modifier = Modifier.fillMaxWidth()) {
                Text("Изменить заметку")
            }
            OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Text("Прочитать сохранённый результат")
            }
            OutlinedButton(onClick = onReanalyse, enabled = photo != null, modifier = Modifier.fillMaxWidth()) {
                Icon(AgroIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Проверить снимок заново текущей моделью")
            }
            Text(
                "Новая проверка создаёт новый результат; этот осмотр в журнале не меняется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SavedFacts(view: SavedInspectionView) {
    val inspection = view.details.inspection
    val facts = listOfNotNull(
        "Оценка: ${reviewText(inspection.userReview)}",
        view.details.plot?.name?.let { "Участок: $it" },
        inspection.row?.let { "Ряд: $it" },
        view.activePhoto?.plantName?.let { "Растение: $it" },
        inspection.lat?.let { lat -> "Координаты: %.5f, %.5f ±%.0f м".format(Locale.ROOT, lat, inspection.lon ?: 0.0, inspection.accuracyM ?: 0.0) },
        inspection.note.takeIf { it.isNotBlank() }?.let { "Заметка: $it" }
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun reviewText(review: String) = when (review) {
    "correct" -> "верно"
    "incorrect" -> "неверно"
    "uncertain" -> "не уверен"
    else -> "без оценки"
}
