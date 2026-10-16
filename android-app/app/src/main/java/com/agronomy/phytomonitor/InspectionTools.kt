package com.agronomy.phytomonitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
internal fun InspectionTools(model: FieldDemoViewModel,onNote: ()->Unit,onSpeak: ()->Unit,speaking: Boolean,speakerStatus: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Полевые инструменты · тестовые",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(value=model.plotName,onValueChange=model::editPlot,label={Text("Участок / место (вручную)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onNote,enabled=!model.busy){Text("Заметка")}
                OutlinedButton(onClick={model.openJournal()}){Text("Журнал осмотров")}
            }
            Text(if(model.noteDraft.isBlank())"Заметка пока пустая" else model.noteDraft)
            Button(onClick={model.saveInspection()},enabled=!model.busy && model.reportName!=null){Text("Сохранить осмотр")}
            Text("Твоя оценка результата на всём снимке")
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("correct" to "Верно","incorrect" to "Неверно","uncertain" to "Не уверен").forEach {(id,label)->
                    FilterChip(selected=model.review==id,onClick={model.assess(id)},enabled=!model.busy && model.reportName!=null,label={Text(label)})
                }
            }
            OutlinedButton(onClick={model.compareModels()},enabled=!model.busy && model.bitmap!=null){Text("Сравнить модели")}
            TextButton(onClick=onSpeak){Text(if(speaking)"Остановить чтение" else "Прочитать результат")}
            Text(speakerStatus,style=MaterialTheme.typography.bodySmall)
            Row {
                Text("Рамки на фото",modifier=Modifier.weight(1f))
                Switch(checked=model.showBoxes,onCheckedChange={model.showBoxes=it})
            }
            model.comparison.forEach {(name,result)->
                Text("$name · ${boxesLabel(result.boxes.size)} · ${result.pipelineMs.toInt()} мс")
                Text(result.boxes.take(3).joinToString("; "){model.detector.labels[it.classId]}.ifBlank {"Нет уверенного результата"},style=MaterialTheme.typography.bodySmall)
            }
            if(model.comparison.isNotEmpty())Text("Один снимок и одинаковые настройки. Совпадение моделей не доказывает диагноз; время — один запуск, не эталон скорости.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun InspectionDialogs(model: FieldDemoViewModel,onDictation: ()->Unit,onCloseNote: ()->Unit,onExport: ()->Unit) {
    if(model.noteOpen)AlertDialog(onDismissRequest=onCloseNote,title={Text("Заметка к снимку")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Автономная диктовка — тестовая. Проверь названия и исправь текст перед сохранением.")
            OutlinedTextField(value=model.noteDraft,onValueChange=model::editNote,modifier=Modifier.fillMaxWidth(),label={Text("Текст заметки")},maxLines=6)
            OutlinedButton(onClick=onDictation){Text(if(model.dictating)"Остановить диктовку" else "Диктовать заметку")}
            Text(if(model.dictating)"Говори заметку. «Закончить заметку» или «стоп» — завершить. Другие фразы здесь записываются текстом." else if(model.noteReviewVoice)"Проверь текст. «Подтвердить» — сохранить осмотр, «отмена» — к снимку." else "Сейчас микрофон для заметки выключен.")
        }
    },confirmButton={TextButton(onClick={onCloseNote();model.saveInspection()}){Text("Сохранить заметку и осмотр")}},dismissButton={TextButton(onClick=onCloseNote){Text("К снимку")}})
    if(model.journalOpen)AlertDialog(onDismissRequest={model.journalOpen=false},title={Text("Журнал осмотров")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value=model.journalQuery,onValueChange={model.journalQuery=it},label={Text("Поиск: участок, заметка, модель")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("all" to "Все","correct" to "Верно","incorrect" to "Неверно","uncertain" to "Не уверен","unrated" to "Без оценки").forEach {(id,label)->
                    FilterChip(selected=model.journalFilter==id,onClick={model.journalFilter=id},label={Text(label)})
                }
            }
            Text("Найдено: ${model.visibleInspections.size} / ${model.journalEntries.size}")
            OutlinedButton(onClick=onExport,enabled=!model.busy && model.journalEntries.isNotEmpty()){Text("Экспорт всего журнала · JSON")}
            Text("В файле — осмотры и ссылки на фотографии; сами фото не включены. Выбери папку в системном окне.",style=MaterialTheme.typography.bodySmall)
            if(model.journalEntries.isEmpty())Text("Пока нет сохранённых осмотров. Дождись анализа и нажми «Сохранить осмотр».")
            model.visibleInspections.forEach {entry->
                val review=when(entry.review){"correct"->"Верно";"incorrect"->"Неверно";"uncertain"->"Не уверен";else->"Без оценки"}
                val modelName=model.detector.models.firstOrNull {it.id==entry.model}?.name ?: entry.model
                Text("${SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(Date(entry.created))} · $modelName")
                Text("${boxesLabel(entry.detections)} · $review")
                if(entry.plot.isNotBlank())Text("Участок: ${entry.plot}")
                if(entry.note.isNotBlank())Text(entry.note)
                TextButton(onClick={model.journalOpen=false;model.openFile(java.io.File(entry.photo))}){Text("Открыть снимок")}
                HorizontalDivider()
            }
        }
    },confirmButton={TextButton(onClick={model.journalOpen=false}){Text("Закрыть журнал")}})
}
