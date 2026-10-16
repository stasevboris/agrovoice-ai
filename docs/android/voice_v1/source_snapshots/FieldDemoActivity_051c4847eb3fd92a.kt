package com.agronomy.phytomonitor

import android.content.ActivityNotFoundException
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agronomy.phytomonitor.ui.theme.AgroVoiceAITheme
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceAction
import java.util.Locale
import kotlin.math.min

class FieldDemoActivity : ComponentActivity() {
    private lateinit var voice: OfflineVoiceController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voice=OfflineVoiceController(applicationContext)
        setContent {
            AgroVoiceAITheme {
                val model: FieldDemoViewModel = viewModel()
                val gallery=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null)model.importPhoto(uri) }
                val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> model.captureComplete(success) }
                val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if(granted && !model.busy)voice.start()
                    else if(!granted)voice.permissionDenied()
                }
                val openGallery: ()->Unit = {gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
                val openCamera: ()->Unit = {
                    try {
                        val file=model.createCapture()
                        camera.launch(FileProvider.getUriForFile(this,"$packageName.files",file))
                    } catch(_:ActivityNotFoundException) {
                        model.captureComplete(false);model.showError("Приложение камеры не найдено")
                    } catch(error:Exception){model.captureComplete(false);model.showError(error.message ?: "Камера недоступна")}
                }
                voice.onCommand = {command ->
                    if(!model.busy)when(command.action) {
                        VoiceAction.CAMERA -> openCamera()
                        VoiceAction.GALLERY -> openGallery()
                        VoiceAction.ANALYZE -> if(model.bitmap!=null)model.analyze() else model.showError("Сначала сделай фото или выбери снимок")
                        VoiceAction.MODEL -> model.setModel(command.value)
                        VoiceAction.CROP -> model.selectCrop(command.value)
                        VoiceAction.CANCEL -> Unit
                    }
                }
                Scaffold { padding ->
                    FieldDemoScreen(model,Modifier.padding(padding),
                        onGallery=openGallery,onCamera=openCamera,voice=voice,
                        onVoice={
                            if(voice.active)voice.stop()
                            else if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)voice.start()
                            else microphone.launch(Manifest.permission.RECORD_AUDIO)
                        })
                }
            }
        }
    }
    override fun onStop(){voice.stop();super.onStop()}
    override fun onDestroy(){voice.close();super.onDestroy()}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldDemoScreen(model: FieldDemoViewModel, modifier: Modifier,
                            onGallery: ()->Unit, onCamera: ()->Unit,
                            voice: OfflineVoiceController, onVoice: ()->Unit) {
    val controlsEnabled=!model.busy && !voice.active
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("AgroVoice AI",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Проверка листьев · без интернета",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        VoiceCommandPanel(voice,onVoice,enabled=!model.busy)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onCamera,enabled=controlsEnabled,modifier=Modifier.weight(1f)){Text("Сделать фото")}
            OutlinedButton(onClick=onGallery,enabled=controlsEnabled,modifier=Modifier.weight(1f)){Text("Из галереи")}
        }
        Text("Модель",style=MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            model.detector.models.forEach { version ->
                FilterChip(selected=model.selectedModel==version.id,onClick={model.setModel(version.id)},label={Text(version.name)},enabled=controlsEnabled)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("all" to "Все культуры","Potato" to "Картофель","Tomato" to "Томат","Cucumber" to "Огурец").forEach { (id,label) ->
                FilterChip(selected=model.crop==id,onClick={model.selectCrop(id)},label={Text(label)},enabled=controlsEnabled)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            val image=model.bitmap
            if(image==null) {
                Box(Modifier.fillMaxWidth().height(220.dp).padding(24.dp),contentAlignment=Alignment.Center) {
                    Text("Сфотографируй лист или выбери готовое фото. Лучше снимать в хорошем свете и без размытия.",style=MaterialTheme.typography.bodyLarge)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(340.dp).background(Color(0xFF17251D))) {
                    Image(image.asImageBitmap(),contentDescription="Выбранное фото растения",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    Canvas(Modifier.fillMaxSize()) {
                        val scale=min(size.width/image.width,size.height/image.height)
                        val offsetX=(size.width-image.width*scale)/2;val offsetY=(size.height-image.height*scale)/2
                        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color=android.graphics.Color.YELLOW;textSize=16.dp.toPx();isFakeBoldText=true
                        }
                        model.result?.boxes?.forEachIndexed { index,box ->
                            val left=offsetX+box.left*scale;val top=offsetY+box.top*scale
                            drawRect(Color(0xFFFFD54F),Offset(left,top),Size((box.right-box.left)*scale,(box.bottom-box.top)*scale),style=Stroke(2.dp.toPx()))
                            drawContext.canvas.nativeCanvas.drawText("${index+1}",left+4.dp.toPx(),(top+20.dp.toPx()).coerceAtMost(size.height-4.dp.toPx()),paint)
                        }
                    }
                }
            }
        }
        Text("Порог уверенности: ${String.format(Locale.US,"%.2f",model.threshold)}",style=MaterialTheme.typography.bodyMedium)
        Slider(value=model.threshold,onValueChange={model.changeThreshold(it)},valueRange=0.1f..0.8f,onValueChangeFinished={model.analyze()},enabled=controlsEnabled)
        if(model.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Обрабатываю фото…",style=MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick={model.analyze()},enabled=model.bitmap!=null && controlsEnabled,modifier=Modifier.fillMaxWidth()){Text("Проверить ещё раз")}
        }
        model.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        model.result?.let { result ->
            Text("${result.model.name} · ${String.format(Locale.US,"%.0f",result.inferenceMs)} мс",style=MaterialTheme.typography.titleMedium)
            if(result.model.classifier!=null) {
                Text("Листьев найдено: ${result.proposedLeaves}, проверено: ${result.classifiedLeaves}",style=MaterialTheme.typography.bodySmall)
                if(result.skippedLeaves>0)Text("Ещё ${result.skippedLeaves} листьев не обработаны. Сними участок крупнее.",color=MaterialTheme.colorScheme.error)
            }
            if(result.boxes.isEmpty()) {
                Text("Не нашёл лист с достаточной уверенностью. Попробуй другой ракурс, модель или порог.")
            } else {
                result.boxes.forEachIndexed { index,box ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${index+1}. ${model.detector.labels[box.classId]}",fontWeight=FontWeight.SemiBold)
                            Text("Уверенность модели: ${String.format(Locale.US,"%.1f",box.confidence*100)}%",style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text("Результат сохранён на телефоне.",style=MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("Предварительные модели. Результат нужно проверять: уверенность не означает вероятность болезни или процент повреждения.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Огурец: модель пока определяет лист без диагноза болезни.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VoiceCommandPanel(voice: OfflineVoiceController, onVoice: ()->Unit, enabled: Boolean) {
    var help by remember {mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(onClick=onVoice,enabled=enabled || voice.active,modifier=Modifier.weight(1f)) {
                    Text(if(voice.active)"Остановить микрофон" else "Голосовая команда")
                }
                TextButton(onClick={help=true}){Text("Фразы")}
            }
            Text(voice.status,style=MaterialTheme.typography.bodyMedium)
            if(voice.heard.isNotBlank())Text("Услышал: ${voice.heard}",style=MaterialTheme.typography.bodySmall)
            Text("Нажми и скажи, например: «сделай фото». Одна команда за нажатие.",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(help)AlertDialog(onDismissRequest={help=false},title={Text("Голосовые команды")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Сделай фото — открыть камеру\nОткрой галерею — выбрать фото\nПроверь фото — повторить анализ")
            Text("Быстрая модель — YOLO26n\nБольшая модель — YOLO26s\nСтарая модель — YOLO11n")
            Text("Картофель · Томаты · Огурцы · Все культуры — выбрать культуру\nСтоп — отменить команду")
            Text("Говори после надписи «Слушаю». Микрофон отключается после команды, через 12 секунд или при уходе из приложения. Аудио не сохраняется.")
        }},confirmButton={TextButton(onClick={help=false}){Text("Понятно")}})
}
