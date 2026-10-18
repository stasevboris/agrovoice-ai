package com.agronomy.phytomonitor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.ml.PhotoFiles
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.ui.BlockHeader
import com.agronomy.phytomonitor.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «1 рамка», «3 рамки», «5 рамок», «11 рамок», «21 рамка». */
internal fun boxesLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "рамок"
        last == 1 -> "рамка"
        last in 2..4 -> "рамки"
        else -> "рамок"
    }
    return "$count $word"
}

internal val cropOptions = listOf("all" to "Все культуры", "Potato" to "Картофель", "Tomato" to "Томат", "Cucumber" to "Огурец")

internal fun cropName(id: String) = cropOptions.firstOrNull { it.first == id }?.second ?: id

/** The threshold is spoken and set in percents; the screen shows the same unit. */
internal fun thresholdPercent(value: Float) = "${(value * 100).toInt()} %"

private val reviewOptions = listOf("correct" to "Верно", "incorrect" to "Неверно", "uncertain" to "Не уверен")

private fun reviewLabel(review: String) = when (review) {
    "correct" -> "Верно"
    "incorrect" -> "Неверно"
    "uncertain" -> "Не уверен"
    else -> "Без оценки"
}

/**
 * The agronomist's own record: a rating of the whole photo, the plot and a dictated note.
 * It sits on the page background without a card: it is the second task of the screen, not a widget.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InspectionTools(model: FieldDemoViewModel, onNote: () -> Unit, onSpeak: () -> Unit, speaking: Boolean, speakerStatus: String) {
    val hasResult = !model.busy && model.reportName != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BlockHeader("Осмотр", AgroIcons.Edit)
        Text(
            "Твоя оценка результата на всём снимке",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            reviewOptions.forEach { (id, label) ->
                FilterChip(
                    selected = model.review == id,
                    onClick = { model.assess(id) },
                    enabled = hasResult,
                    label = { Text(label) },
                    leadingIcon = if (model.review == id) {
                        { Icon(AgroIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    }
                )
            }
        }
        OutlinedTextField(
            value = model.plotName,
            onValueChange = model::editPlot,
            label = { Text("Участок / место (вручную)") },
            leadingIcon = { Icon(AgroIcons.Place, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            onClick = onNote,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (model.noteDraft.isBlank()) "Добавить заметку" else model.noteDraft,
                style = MaterialTheme.typography.bodyMedium,
                color = if (model.noteDraft.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(14.dp)
            )
        }
        Button(onClick = { model.saveInspection() }, enabled = hasResult, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(AgroIcons.Save, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сохранить осмотр")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolButton("Заметка", AgroIcons.Edit, enabled = !model.busy, onClick = onNote, modifier = Modifier.width(104.dp))
            ToolButton(
                if (speaking) "Стоп" else "Прочитать",
                if (speaking) AgroIcons.Stop else AgroIcons.Speaker,
                enabled = true,
                onClick = onSpeak,
                modifier = Modifier.weight(1f)
            )
            ToolButton("Журнал", AgroIcons.Journal, enabled = true, onClick = { model.openJournal() }, modifier = Modifier.width(104.dp))
        }
        Text(speakerStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Square-ish button with the icon above a short label; three of them fit in one row. */
@Composable
private fun ToolButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
internal fun InspectionDialogs(model: FieldDemoViewModel, onDictation: () -> Unit, onCloseNote: () -> Unit) {
    if (!model.noteOpen) {
        return
    }
    AlertDialog(
        onDismissRequest = onCloseNote,
        icon = { Icon(AgroIcons.Edit, contentDescription = null) },
        title = { Text("Заметка к снимку") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Автономная диктовка — тестовая. Проверь названия и исправь текст перед сохранением.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = model.noteDraft,
                    onValueChange = model::editNote,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Текст заметки") },
                    maxLines = 6
                )
                OutlinedButton(onClick = onDictation, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (model.dictating) AgroIcons.Stop else AgroIcons.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (model.dictating) "Остановить диктовку" else "Диктовать заметку")
                }
                val hint = when {
                    model.dictating -> "Говори заметку. «Закончить заметку» или «стоп» — завершить. Другие фразы здесь записываются текстом."
                    model.noteReviewVoice -> "Проверь текст. «Подтвердить» — сохранить осмотр, «отмена» — к снимку."
                    else -> "Сейчас микрофон для заметки выключен."
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCloseNote(); model.saveInspection() }) { Text("Сохранить заметку и осмотр") }
        },
        dismissButton = { TextButton(onClick = onCloseNote) { Text("К снимку") } }
    )
}

/** Full-screen journal of saved inspections with search, filter and JSON export. */
@Composable
internal fun JournalScreen(model: FieldDemoViewModel, onBack: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AgroIcons.Back, contentDescription = "Закрыть журнал") }
            Text("Журнал осмотров", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(104.dp))
            TextButton(onClick = onExport, enabled = !model.busy && model.journalEntries.isNotEmpty()) {
                Icon(AgroIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("JSON")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = model.journalQuery,
                    onValueChange = { model.journalQuery = it },
                    label = { Text("Поиск: участок, заметка, модель") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "Все", "correct" to "Верно", "incorrect" to "Неверно", "uncertain" to "Не уверен", "unrated" to "Без оценки").forEach { (id, label) ->
                        FilterChip(selected = model.journalFilter == id, onClick = { model.journalFilter = id }, label = { Text(label) })
                    }
                }
            }
            item {
                Text(
                    "Найдено: ${model.visibleInspections.size} / ${model.journalEntries.size}. Экспорт JSON — осмотры и ссылки на фото, сами фото не включены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.journalEntries.isEmpty()) {
                item {
                    Text("Пока нет сохранённых осмотров. Дождись анализа и нажми «Сохранить осмотр».", style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(model.visibleInspections, key = { it.id }) { entry ->
                JournalEntryCard(model, entry, onOpen = {
                    model.journalOpen = false
                    model.openFile(File(entry.photo))
                })
            }
        }
    }
}

@Composable
private fun JournalEntryCard(model: FieldDemoViewModel, entry: InspectionEntry, onOpen: () -> Unit) {
    val modelName = model.detector.models.firstOrNull { it.id == entry.model }?.name ?: entry.model
    val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.created))
    val colors = MaterialTheme.colorScheme
    val (reviewContainer, reviewContent) = when (entry.review) {
        "correct" -> colors.primaryContainer to colors.onPrimaryContainer
        "incorrect" -> colors.errorContainer to colors.onErrorContainer
        "uncertain" -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp)) {
            JournalThumbnail(entry.photo)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(date, style = MaterialTheme.typography.titleSmall)
                Text("$modelName · ${boxesLabel(entry.detections)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Pill(reviewLabel(entry.review), reviewContainer, reviewContent)
                if (entry.plot.isNotBlank()) {
                    Text("Участок: ${entry.plot}", style = MaterialTheme.typography.bodyMedium)
                }
                if (entry.note.isNotBlank()) {
                    Text(entry.note, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
                    Text("Открыть снимок")
                }
            }
        }
    }
}

@Composable
private fun JournalThumbnail(path: String) {
    val image by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { PhotoFiles.thumbnail(File(path), 200) }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(84.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        if (bitmap == null) {
            Icon(AgroIcons.Leaf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Image(bitmap.asImageBitmap(), contentDescription = "Снимок осмотра", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
