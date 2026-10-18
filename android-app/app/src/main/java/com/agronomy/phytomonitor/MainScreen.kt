package com.agronomy.phytomonitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.photos.PhotoFlowViewModel
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.ui.ComparisonRow
import com.agronomy.phytomonitor.ui.ConfidenceDialog
import com.agronomy.phytomonitor.ui.EmptyPhoto
import com.agronomy.phytomonitor.ui.LeafResultRow
import com.agronomy.phytomonitor.ui.MessageBanner
import com.agronomy.phytomonitor.ui.PhotoWithDetections
import com.agronomy.phytomonitor.ui.BlockHeader
import com.agronomy.phytomonitor.ui.AgroMotion
import com.agronomy.phytomonitor.ui.rememberReducedMotion
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceCommands
import java.util.Locale

/**
 * One screen, two zones: what the camera sees (photo and its result) and what the agronomist adds
 * (rating, plot, note). Capture and voice live in a pinned bar at thumb height, so the result never
 * moves away while the hand reaches for the next photo.
 */
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
    onNote: () -> Unit,
    onSpeak: () -> Unit,
    speaking: Boolean,
    speakerStatus: String
) {
    val controlsEnabled = !model.busy
    val reducedMotion = rememberReducedMotion()
    val scroll = rememberScrollState()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Tablets and landscape get two columns and a side rail: the photo should not sit above the result
        // with wide empty margins on both sides.
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                onJournal = { model.openJournal() },
                onSettings = { settingsOpen = true },
                status = if (model.busy) "Анализ…" else model.selectedModelName()
            )
            if (wide) {
                val scrollPhoto = rememberScrollState()
                Row(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .weight(1.25f)
                            .verticalScroll(scrollPhoto)
                            .padding(start = 16.dp, end = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PhotoZone(model, maxHeight = 640.dp)
                        ErrorBanner(model)
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(scroll)
                            .padding(start = 8.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ResultReveal(model.result != null, reducedMotion) {
                            model.result?.let { ResultSection(model, it, controlsEnabled) }
                        }
                        InspectionTools(model, onNote, onSpeak, speaking, speakerStatus)
                        VoiceStatusLine(voice, flow, listening = voice.active || flow.dialogVoice || flow.continuousVoice)
                    }
                    ActionRail(
                        voice = voice,
                        flow = flow,
                        enabled = controlsEnabled,
                        onCamera = onCamera,
                        onGallery = onGallery,
                        onVoice = onVoice,
                        onContinuous = onContinuous
                    )
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PhotoZone(model)
                    if (model.busy) {
                        BusyLine()
                    }
                    ErrorBanner(model)
                    ResultReveal(model.result != null, reducedMotion) {
                        model.result?.let { ResultSection(model, it, controlsEnabled) }
                    }
                    InspectionTools(model, onNote, onSpeak, speaking, speakerStatus)
                    Spacer(Modifier.height(8.dp))
                }
                BottomArea(voice, flow, onVoice, onContinuous, controlsEnabled, onCamera, onGallery)
            }
        }
    }
    if (settingsOpen) {
        AnalysisSheet(model, controlsEnabled) { settingsOpen = false }
    }
}

@Composable
private fun PhotoZone(model: FieldDemoViewModel, maxHeight: androidx.compose.ui.unit.Dp = 340.dp) {
    val image = model.bitmap
    if (image == null) {
        Spacer(Modifier.height(4.dp))
        EmptyPhoto()
    } else {
        PhotoWithDetections(image, model.result?.boxes.orEmpty(), model.showBoxes, busy = model.busy, maxHeight = maxHeight)
        model.photoQuality?.advice?.let { MessageBanner(it, isError = false) }
    }
}

@Composable
private fun ErrorBanner(model: FieldDemoViewModel) {
    model.message?.takeIf { model.messageIsError }?.let { MessageBanner(it, isError = true) }
}

/** Icons along the right edge: the same three actions as the bottom bar, laid out for a tablet or landscape. */
@Composable
private fun ActionRail(
    voice: OfflineVoiceController,
    flow: PhotoFlowViewModel,
    enabled: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onVoice: () -> Unit,
    onContinuous: (Boolean) -> Unit
) {
    val listening = voice.active || flow.dialogVoice || flow.continuousVoice
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(88.dp)
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledIconButton(onClick = onCamera, enabled = enabled, modifier = Modifier.size(64.dp)) {
                Icon(AgroIcons.Camera, contentDescription = "Сделать фото")
            }
            FilledTonalIconButton(onClick = onGallery, enabled = enabled, modifier = Modifier.size(56.dp)) {
                Icon(AgroIcons.Gallery, contentDescription = "Из галереи")
            }
            FilledTonalIconButton(
                onClick = onVoice,
                modifier = Modifier
                    .size(56.dp)
                    .then(if (listening) Modifier.background(MaterialTheme.colorScheme.tertiary, CircleShape) else Modifier)
            ) {
                Icon(
                    if (listening) AgroIcons.Stop else AgroIcons.Mic,
                    contentDescription = if (listening) "Остановить микрофон" else "Голосовая команда",
                    tint = if (listening) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Switch(
                checked = flow.continuousVoice,
                onCheckedChange = onContinuous,
                modifier = Modifier
                    .semantics { contentDescription = "Постоянно слушать" }
                    .testTag("continuous_voice")
            )
            TextButton(onClick = { flow.helpOpen = true }) {
                Text("Фразы", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Compact top bar: the product name, the journal and the analysis settings. Nothing else competes with the photo. */
@Composable
private fun TopBar(onJournal: () -> Unit, onSettings: () -> Unit, status: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(AgroIcons.Leaf, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("AgroVoice", style = MaterialTheme.typography.titleLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onJournal) {
            Icon(AgroIcons.Journal, contentDescription = "Журнал")
        }
        IconButton(onClick = onSettings) {
            Icon(AgroIcons.Tune, contentDescription = "Настройки анализа")
        }
    }
}

@Composable
private fun BusyLine() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text("Обрабатываю фото…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultSection(model: FieldDemoViewModel, result: DetectionResult, controlsEnabled: Boolean) {
    var explain by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BlockHeader(title = "Результат", icon = AgroIcons.Leaf)
        Text(
            "${result.model.name} · ${formatSeconds(result.inferenceMs)} · предварительная модель: " +
                "уверенность не означает вероятность болезни",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (result.model.classifier != null) {
            Text(
                "Листьев найдено: ${result.proposedLeaves}, проверено: ${result.classifiedLeaves}",
                style = MaterialTheme.typography.bodySmall
            )
            if (result.skippedLeaves > 0) {
                MessageBanner("Ещё ${result.skippedLeaves} листьев не обработаны. Сними участок крупнее.", isError = false)
            }
        }
        if (result.boxes.isEmpty()) {
            MessageBanner(NO_DETECTIONS_ADVICE, isError = false)
        } else {
            result.boxes.forEachIndexed { index, box ->
                LeafResultRow(
                    number = index + 1,
                    label = model.detector.labels[box.classId],
                    confidence = box.confidence,
                    prominent = index == 0,
                    showCrop = true,
                    onExplain = { explain = true }
                )
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
    }
    if (explain) {
        ConfidenceDialog { explain = false }
    }
}

private val Russian = Locale.forLanguageTag("ru")

/** «1136 мс» is hard to read in the field: show tenths of a second with a Russian decimal comma. */
internal fun formatSeconds(milliseconds: Double): String =
    String.format(Russian, "%.1f с", milliseconds / 1000.0)

/**
 * Pinned capture bar: the camera is the one wide action; the gallery and the microphone are
 * icon actions with accessible names. The voice line sits just above, so the status never pushes
 * the buttons around while listening.
 */
@Composable
private fun BottomArea(
    voice: OfflineVoiceController,
    flow: PhotoFlowViewModel,
    onVoice: () -> Unit,
    onContinuous: (Boolean) -> Unit,
    enabled: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    val listening = voice.active || flow.dialogVoice || flow.continuousVoice
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VoiceStatusLine(voice, flow, listening)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onCamera,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Icon(AgroIcons.Camera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Сделать фото", maxLines = 1)
                }
                FilledTonalIconButton(
                    onClick = onGallery,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(AgroIcons.Gallery, contentDescription = "Из галереи", modifier = Modifier.size(24.dp))
                }
                FilledTonalIconButton(
                    onClick = onVoice,
                    modifier = Modifier
                        .size(56.dp)
                        .then(if (listening) Modifier.background(MaterialTheme.colorScheme.tertiary, CircleShape) else Modifier)
                ) {
                    Icon(
                        if (listening) AgroIcons.Stop else AgroIcons.Mic,
                        contentDescription = if (listening) "Остановить микрофон" else "Голосовая команда",
                        tint = if (listening) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val wholeRow = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .toggleable(value = flow.continuousVoice, role = Role.Switch, onValueChange = onContinuous)
                    .padding(vertical = 2.dp)
                    .testTag("continuous_voice")
                Row(wholeRow, verticalAlignment = Alignment.CenterVertically) {
                    Text("Постоянно слушать", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = flow.continuousVoice, onCheckedChange = null)
                }
                TextButton(onClick = { flow.helpOpen = true }) {
                    Text("Фразы")
                }
            }
        }
    }
    if (flow.helpOpen) {
        VoiceHelpDialog { flow.helpOpen = false }
    }
}

/** One line of context for the microphone: status while idle, «Услышал» while listening. */
@Composable
private fun VoiceStatusLine(voice: OfflineVoiceController, flow: PhotoFlowViewModel, listening: Boolean) {
    val text = when {
        voice.heard.isNotBlank() -> "Услышал: ${voice.heard}"
        listening -> voice.status
        else -> "Голос работает без интернета"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
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

/**
 * Analysis settings live in a sheet, not on the main screen: nine experimental models and a
 * threshold are a laboratory tool, the field screen needs the photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisSheet(model: FieldDemoViewModel, enabled: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnalysisSheetContent(model, enabled)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AnalysisSheetContent(model: FieldDemoViewModel, enabled: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки анализа", style = MaterialTheme.typography.titleLarge)
        Text(
            "${model.selectedModelName()} · ${cropName(model.crop)} · порог ${thresholdPercent(model.threshold)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Модель", style = MaterialTheme.typography.labelLarge)
        ModelChips(model, enabled)
        Text("Культура", style = MaterialTheme.typography.labelLarge)
        CropChips(model, enabled)
        Text("Порог уверенности: ${thresholdPercent(model.threshold)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = model.threshold,
            onValueChange = { model.changeThreshold(it) },
            valueRange = 0.1f..0.8f,
            onValueChangeFinished = { model.analyze() },
            enabled = enabled
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .toggleable(value = model.showBoxes, role = Role.Switch, onValueChange = { model.showBoxes = it })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Рамки на фото", modifier = Modifier.weight(1f))
            Switch(checked = model.showBoxes, onCheckedChange = null)
        }
        OutlinedButton(
            onClick = { model.compareModels() },
            enabled = enabled && model.bitmap != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(AgroIcons.Compare, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Сравнить модели")
        }
        ComparisonList(model)
        Text(
            "Огурец: модель пока определяет лист без диагноза болезни.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropChips(model: FieldDemoViewModel, enabled: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        cropOptions.forEach { (id, label) ->
            FilterChip(selected = model.crop == id, onClick = { model.selectCrop(id) }, label = { Text(label) }, enabled = enabled)
        }
    }
}

@Composable
private fun ComparisonList(model: FieldDemoViewModel) {
    if (model.comparison.isEmpty()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сравнение моделей", style = MaterialTheme.typography.titleMedium)
        model.comparison.forEach { (name, result) ->
            val labels = result.boxes.take(3).joinToString("; ") { model.detector.labels[it.classId] }
            ComparisonRow(
                name = name,
                summary = labels.ifBlank { "Нет уверенного результата" },
                meta = "${boxesLabel(result.boxes.size)} · ${formatSeconds(result.pipelineMs)}"
            )
        }
        Text(
            "Один снимок и одинаковые настройки. Совпадение моделей не доказывает диагноз; время — один запуск, не эталон скорости.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The result block appears with one short slide: the eye follows the photo downwards to the diagnosis.
 * With system animations off the block is simply there.
 */
@Composable
private fun ResultReveal(visible: Boolean, reducedMotion: Boolean, content: @Composable () -> Unit) {
    if (reducedMotion) {
        if (visible) {
            content()
        }
        return
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(AgroMotion.RESULT_MS)) +
            slideInVertically(tween(AgroMotion.RESULT_MS)) { height -> height / 6 },
        exit = fadeOut(tween(AgroMotion.RESULT_MS / 2))
    ) {
        content()
    }
}
