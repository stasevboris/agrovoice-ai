package com.agronomy.phytomonitor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.photos.PhotoFlowViewModel
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.ui.EmptyPhoto
import com.agronomy.phytomonitor.ui.LeafResultRow
import com.agronomy.phytomonitor.ui.MessageBanner
import com.agronomy.phytomonitor.ui.PhotoWithDetections
import com.agronomy.phytomonitor.ui.Pill
import com.agronomy.phytomonitor.ui.SectionCard
import com.agronomy.phytomonitor.ui.preliminaryColors
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceCommands
import java.util.Locale

@Composable
internal fun FieldDemoScreen(
    model: FieldDemoViewModel,
    modifier: Modifier,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    voice: OfflineVoiceController,
    flow: PhotoFlowViewModel,
    onVoice: () -> Unit,
    onContinuous: (Boolean) -> Unit,
    onSystemGallery: () -> Unit,
    onNote: () -> Unit,
    onSpeak: () -> Unit,
    speaking: Boolean,
    speakerStatus: String
) {
    val controlsEnabled = !model.busy
    val scroll = rememberScrollState()
    var handledScroll by remember { mutableIntStateOf(flow.scrollEvent) }
    LaunchedEffect(flow.scrollEvent) {
        if (handledScroll != flow.scrollEvent) {
            handledScroll = flow.scrollEvent
            when (flow.scrollDirection) {
                "top" -> scroll.animateScrollTo(0)
                "bottom" -> scroll.animateScrollTo(scroll.maxValue)
                else -> {
                    val step = if (flow.scrollDirection == "down") 600 else -600
                    scroll.animateScrollTo((scroll.value + step).coerceIn(0, scroll.maxValue))
                }
            }
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader()
        VoiceCommandPanel(voice, flow, onVoice, onContinuous, compact = model.bitmap != null)
        val image = model.bitmap
        if (image == null) {
            EmptyPhoto()
        } else {
            PhotoWithDetections(image, model.result?.boxes.orEmpty(), model.showBoxes)
        }
        CaptureActions(controlsEnabled, onCamera, onGallery, onSystemGallery)
        if (model.busy) {
            BusyCard()
        }
        model.message?.let { MessageBanner(it, isError = true) }
        model.result?.let { ResultSection(model, it, controlsEnabled) }
        InspectionTools(model, onNote, onSpeak, speaking, speakerStatus)
        AnalysisSettings(model, controlsEnabled)
        Text(
            "Предварительные модели. Результат нужно проверять: уверенность не означает вероятность болезни или процент повреждения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Огурец: модель пока определяет лист без диагноза болезни.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(AgroIcons.Leaf, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("AgroVoice AI", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Проверка листьев голосом",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Pill("без интернета", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun CaptureActions(enabled: Boolean, onCamera: () -> Unit, onGallery: () -> Unit, onSystemGallery: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onCamera,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
            ) {
                Icon(AgroIcons.Camera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Сделать фото", maxLines = 1)
            }
            FilledTonalButton(
                onClick = onGallery,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
            ) {
                Icon(AgroIcons.Gallery, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Из галереи", maxLines = 1)
            }
        }
        TextButton(onClick = onSystemGallery, enabled = enabled) {
            Text("Системный выбор фото")
        }
    }
}

@Composable
private fun BusyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Обрабатываю фото…", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultSection(model: FieldDemoViewModel, result: DetectionResult, controlsEnabled: Boolean) {
    val (pillContainer, pillContent) = preliminaryColors()
    SectionCard(
        title = "Результат",
        icon = AgroIcons.Leaf,
        trailing = { Pill("предварительно", pillContainer, pillContent) }
    ) {
        Text(
            "${result.model.name} · ${String.format(Locale.US, "%.0f", result.inferenceMs)} мс",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (result.model.classifier != null) {
            Text(
                "Листьев найдено: ${result.proposedLeaves}, проверено: ${result.classifiedLeaves}",
                style = MaterialTheme.typography.bodySmall
            )
            if (result.skippedLeaves > 0) {
                MessageBanner("Ещё ${result.skippedLeaves} листьев не обработаны. Сними участок крупнее.", isError = true)
            }
        }
        if (result.boxes.isEmpty()) {
            MessageBanner("Не нашёл лист с достаточной уверенностью. Попробуй другой ракурс, модель или порог.", isError = false)
        } else {
            result.boxes.forEachIndexed { index, box ->
                LeafResultRow(index + 1, model.detector.labels[box.classId], box.confidence)
            }
        }
        OutlinedButton(
            onClick = { model.analyze() },
            enabled = model.bitmap != null && controlsEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(AgroIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Проверить ещё раз")
        }
        Text(
            "Результат сохранён на телефоне.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun VoiceCommandPanel(
    voice: OfflineVoiceController,
    flow: PhotoFlowViewModel,
    onVoice: () -> Unit,
    onContinuous: (Boolean) -> Unit,
    context: String = "main",
    compact: Boolean = false
) {
    val listening = voice.active || flow.dialogVoice || flow.continuousVoice
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (listening) colors.primaryContainer else colors.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onVoice,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 48.dp else 56.dp),
                    colors = if (listening) {
                        ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Icon(if (listening) AgroIcons.Stop else AgroIcons.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (listening) "Остановить микрофон" else "Голосовая команда")
                }
                TextButton(onClick = { flow.helpOpen = true }) {
                    Text("Фразы")
                }
            }
            // The whole row toggles: a large target for gloves, and TalkBack reads the label with the switch.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .toggleable(value = flow.continuousVoice, role = Role.Switch, onValueChange = onContinuous)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("continuous_voice"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Постоянно слушать", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = flow.continuousVoice, onCheckedChange = null)
            }
            Text(voice.status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (voice.heard.isNotBlank()) {
                Text("Услышал: ${voice.heard}", style = MaterialTheme.typography.bodyMedium)
            }
            if (!compact) {
                val hint = when (context) {
                    "camera" -> "Наведи камеру → «подтвердить»."
                    "gallery" -> "Номер → просмотр → «подтвердить»."
                    else -> "«Сделай фото» или «открой галерею»."
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
    if (flow.helpOpen) {
        VoiceHelpDialog(onClose = { flow.helpOpen = false })
    }
}

@Composable
private fun VoiceHelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(AgroIcons.Mic, contentDescription = null) },
        title = { Text("Голосовые команды") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HelpGroup("Снимок", "Сделай фото — камера, наведи и скажи «подтвердить»\nСделай фото сразу — сразу снять\nОткрой галерею — фото с номерами\nФото номер три / три — показать снимок\nСледующее фото / предыдущее фото — листать\nПервое фото — самое новое; последнее — самое старое\nПодтвердить — снять в камере или выбрать в галерее\nОтмена / назад — вернуться\nПроверь фото — анализ выбранного снимка")
                HelpGroup("Модели", "Модель один — YOLO11n\nМодель два — YOLO26n (быстрая модель)\nМодель три — MobileNet\nМодель четыре — EfficientNet\nМодель пять — маска + MobileNet\nМодель шесть — ученик\nМодель семь — YOLO26s (большая модель)\nМодель восемь — DETR, пример\nМодель девять — YOLO26m, технический пилот")
                HelpGroup("Осмотр", "Добавь заметку — диктовка к снимку\nЗакончить заметку / стоп — остановить диктовку, затем «подтвердить» — сохранить\nСохрани осмотр — запись в журнал\nОткрой журнал / покажи историю\nРезультат верный / результат неверный / не уверен — твоя оценка\nСравни модели — один снимок и одинаковые настройки\nПрочитай результат — автономный русский голос, если установлен\nПокажи рамки / убери рамки")
                HelpGroup("Настройки и экран", "Картофель · Томаты · Огурцы · Все культуры\nПорог двадцать пять [процентов] — от 10 до 80\nУвеличь порог / уменьши порог — шаг 5\nПрокрути вниз / вверх · в начало / в конец\nПомощь / покажи команды · понятно — закрыть подсказку\nСтоп / выключи голос — выключить слушание\nСлушай постоянно — включить постоянный режим\nЗакрой приложение — выйти")
                HorizontalDivider()
                Text(
                    "Переключатель слушает непрерывно, пока приложение на экране, до выключения. В фоне микрофон выключен; при возвращении включится, если переключатель остался включён. Без переключателя — одна команда; в камере/галерее диалог до 2 минут. В системном выборе фото голосом управлять нельзя: используй галерею с номерами. Аудио не сохраняется.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Понятно") } }
    )
}

@Composable
private fun HelpGroup(title: String, lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(lines, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelChips(model: FieldDemoViewModel, enabled: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        model.detector.models.forEach { version ->
            val number = VoiceCommands.modelIds.indexOf(version.id) + 1
            FilterChip(
                selected = model.selectedModel == version.id,
                onClick = { model.setModel(version.id) },
                label = { Text("$number. ${version.name}") },
                enabled = enabled
            )
        }
    }
}
