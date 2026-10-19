package com.agronomy.phytomonitor.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.ml.CropVocabulary

@Composable
fun KnowledgeScreen(model: KnowledgeViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onRead: () -> Unit,
                    onCrop: (String) -> Unit, onQuery: (String) -> Unit) {
    val state by model.state.collectAsState()
    val links = LocalUriHandler.current
    var cropsOpen by remember { mutableStateOf(false) }
    val card = state.selected
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row {
                TextButton(onClick = onBack) { Text("Назад") }
                TextButton(onClick = onRead) { Text(if (card != null) "Прочитать справку" else "Прочитать список") }
            }
            Text(card?.name ?: "Справочник", style = MaterialTheme.typography.headlineSmall)
        }
        if (state.loading) item { Text("Загрузка справочника…") }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (card == null && state.selectedCode == null) {
            item {
                OutlinedTextField(state.query, onQuery, label = { Text("Поиск по справочнику") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = { cropsOpen = true }) { Text(CropVocabulary.names[state.crop] ?: "Все культуры") }
                DropdownMenu(cropsOpen, onDismissRequest = { cropsOpen = false }) {
                    (mapOf("all" to "Все культуры") + CropVocabulary.names).forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { onCrop(id); cropsOpen = false })
                    }
                }
                Text("Черновые описания не являются проверенными рекомендациями по лечению.", style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(state.visible, key = { _, entry -> entry.code }) { index, entry ->
                Card(onClick = { onOpen(entry.code) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${index + 1}. ${entry.name}")
                        Text(if (entry.reviewed()) "Проверено специалистом" else "Черновик / требуется проверка",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!state.loading && state.visible.isEmpty()) item { Text("Подходящих карточек нет. Измени поиск или культуру.") }
        } else if (card != null) {
            item {
                Text(if (card.reviewed()) "Проверено: ${card.verifiedBy}" else "Черновик. Проверка специалистом не выполнена или устарела.",
                    color = MaterialTheme.colorScheme.primary)
            }
            for ((section, statements) in card.sections) {
                if (statements.isNotEmpty()) item {
                    Text(KnowledgeCatalog.sectionNames.getValue(section), style = MaterialTheme.typography.titleMedium)
                    statements.forEach { statement ->
                        Text(statement.text)
                        Text("${statement.source}, ${statement.locator}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Проверенные назначения препаратов пока недоступны. Сверяйтесь с этикеткой препарата и действующим реестром РБ.")
                if (card.missing.isNotEmpty()) Text("Не заполнено: " + card.missing.joinToString { KnowledgeCatalog.gapNames[it] ?: it },
                    style = MaterialTheme.typography.bodySmall)
            }
            items(card.sources, key = { it.id }) { source ->
                Text("${source.id}: ${source.title}\n${source.edition}\n${source.url}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { links.openUri(source.url) }) { Text("Открыть источник онлайн") }
            }
        } else if (!state.loading) item { Text("Карточка не найдена.") }
        item { Text(state.catalog?.version.orEmpty(), style = MaterialTheme.typography.bodySmall) }
    }
}
