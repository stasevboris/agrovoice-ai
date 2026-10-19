package com.agronomy.phytomonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.journal.JournalState
import com.agronomy.phytomonitor.ui.MessageBanner
import com.agronomy.phytomonitor.ui.PhotoWithDetections
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Two dated observations, without deriving recovery from colour, labels or confidence. */
@Composable
internal fun PlantHistoryScreen(state: JournalState, onBack: () -> Unit, onOpen: (Int) -> Unit,
    onCompare: (String) -> Unit, onRead: () -> Unit) {
    var first by rememberSaveable(state.historyPlantId) { mutableStateOf("1") }
    var second by rememberSaveable(state.historyPlantId) { mutableStateOf("") }
    LaunchedEffect(state.history.size) { second = state.history.size.toString() }
    val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val scroll = rememberLazyListState()
    LaunchedEffect(state.scrollSequence) {
        val target = when (state.scrollDirection) {
            "top" -> 0
            "bottom" -> scroll.layoutInfo.totalItemsCount - 1
            "up" -> scroll.firstVisibleItemIndex - 1
            "down" -> scroll.firstVisibleItemIndex + 1
            else -> return@LaunchedEffect
        }
        scroll.animateScrollToItem(target.coerceIn(0, (scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)))
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Назад") }
            Text("История растения", style = MaterialTheme.typography.headlineSmall)
            state.history.firstOrNull()?.let { Text("${it.plant} · ${it.plot.ifBlank { "Без участка" }}") }
            Text("Сравните фотографии по датам. Разные условия съёмки и модели могут менять результат; это не оценка выздоровления.")
            state.message?.let { MessageBanner(it, isError = state.messageIsError) }
            if (state.busy) Text("Загружаю осмотры…")
        }
        itemsIndexed(state.comparison, key = { _, view -> "comparison:${view.details.inspection.id}" }) { _, view ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(date.format(Date(view.details.inspection.createdAt)), style = MaterialTheme.typography.titleMedium)
                val photo = view.photo
                if (photo == null) Text("Фото недоступно. Запись и результат сохранены.")
                else PhotoWithDetections(photo, view.boxes, showBoxes = true)
                Text("Модель: ${view.details.inspection.modelVersion}")
                Text(view.labels.joinToString("; ").ifEmpty { "Нет сохранённых обнаружений" })
                Text(view.details.inspection.note)
            }
        }
        item {
            OutlinedTextField(first, { first = it.filter(Char::isDigit).take(6) }, label = { Text("Первый номер осмотра") }, singleLine = true)
            OutlinedTextField(second, { second = it.filter(Char::isDigit).take(6) }, label = { Text("Второй номер осмотра") }, singleLine = true)
            OutlinedButton(onClick = { onCompare("$first:$second") }, enabled = !state.busy && state.history.size >= 2) { Text("Сравнить даты") }
            OutlinedButton(onClick = onRead, enabled = !state.busy && state.comparison.isNotEmpty()) { Text("Прочитать сравниваемые осмотры") }
            Text("Голосом: «сравни осмотры один и два», «открой осмотр один», «прочитай результат».")
            if (!state.busy && state.history.size < 2) Text("Для сравнения нужны два осмотра одного растения.")
        }
        itemsIndexed(state.history, key = { _, entry -> entry.id }) { index, entry ->
            OutlinedButton(onClick = { onOpen(index + 1) }, modifier = Modifier.fillMaxWidth()) {
                Text("${index + 1}. ${date.format(Date(entry.created))} · ${entry.model}")
            }
        }
    }
}
