package com.agronomy.phytomonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.agronomy.phytomonitor.journal.PlotsViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.journal.PlotEntity
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.voice.VoiceCommands

/**
 * The farm's plots: choose the current one, add, rename or remove. Voice: «участок <название>»,
 * «ряд четырнадцать», «назад».
 */
@Composable
internal fun PlotsScreen(model: PlotsViewModel, currentPlot: String, row: String, onBack: () -> Unit, onSelect: (String) -> Unit) {
    val state by model.state.collectAsState()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<PlotEntity?>(null) }
    var removing by remember { mutableStateOf<PlotEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AgroIcons.Back, contentDescription = "Закрыть участки") }
            Text("Участки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(120) },
                        label = { Text("Новый участок") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        model.createPlot(newName)
                        newName = ""
                    }, enabled = newName.isNotBlank() && !state.busy) {
                        Text("Добавить")
                    }
                }
            }
            item {
                Text(
                    "Текущий: ${currentPlot.ifBlank { "не выбран" }}${if (row.isBlank()) "" else ", ряд $row"}. " +
                        "Голосом: «участок …» с названием из списка, «ряд …» с номером.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.plots.isEmpty()) {
                item { Text("Участков пока нет. Добавьте первый или впишите название на экране осмотра.", style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.plots, key = { it.plot.id }) { summary ->
                PlotCard(
                    plot = summary.plot,
                    inspections = summary.inspections,
                    current = summary.plot.name == currentPlot,
                    onSelect = { onSelect(summary.plot.name) },
                    onRename = { renaming = summary.plot },
                    onDelete = { removing = summary.plot }
                )
            }
        }
    }
    renaming?.let { plot ->
        RenamePlotDialog(plot, onDismiss = { renaming = null }) { name ->
            model.renamePlot(plot.id, name)
            renaming = null
        }
    }
    removing?.let { plot ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Удалить участок «${plot.name}»?") },
            text = { Text("Осмотры останутся в журнале, но без участка. Отменить удаление нельзя.") },
            confirmButton = {
                TextButton(onClick = {
                    model.deletePlot(plot.id)
                    removing = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Оставить") } }
        )
    }
}

@Composable
private fun PlotCard(plot: PlotEntity, inspections: Int, current: Boolean, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (current) colors.primaryContainer else colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plot.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Осмотров: $inspections · голосом: «участок ${VoiceCommands.spokenPlot(plot.name)}»" + if (current) " · текущий" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            IconButton(onClick = onRename) { Icon(AgroIcons.Edit, contentDescription = "Переименовать ${plot.name}") }
            IconButton(onClick = onDelete) { Icon(AgroIcons.Close, contentDescription = "Удалить ${plot.name}") }
        }
    }
}

@Composable
private fun RenamePlotDialog(plot: PlotEntity, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(plot.id) { mutableStateOf(plot.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать участок") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true, label = { Text("Название") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
