package com.agronomy.phytomonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.ViewModelProvider
import com.agronomy.phytomonitor.photos.*
import com.agronomy.phytomonitor.ui.theme.AgroVoiceAITheme
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min

class FieldDemoActivity : ComponentActivity() {
    private lateinit var voice: OfflineVoiceController
    internal lateinit var photoFlow: PhotoFlowViewModel; private set
    internal lateinit var fieldModel: FieldDemoViewModel; private set
    private var foreground by mutableStateOf(false)
    private var galleryWithVoice=false
    private var automaticCamera=false
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(granted && !fieldModel.busy && foreground) {
            if(photoFlow.galleryOpen)photoFlow.dialogVoice=true else voice.start()
        } else if(!granted)voice.permissionDenied()
    }
    private val mediaPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(LocalPhotoGallery.hasAccess(this))photoFlow.openGallery(galleryWithVoice)
        else fieldModel.showError("Разреши доступ к фотографиям для галереи с номерами. Можно использовать системный выбор фото.")
    }
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(hasCameraPermission())photoFlow.openCamera(automaticCamera)
        else fieldModel.showError("Разреши доступ к камере, чтобы сделать фото.")
    }
    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT>=29 || ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED)
    private fun openCamera(automatic: Boolean) {
        voice.stop();automaticCamera=automatic
        if(photoFlow.cameraOpen)return
        if(hasCameraPermission())photoFlow.openCamera(automatic)
        else cameraPermission.launch(if(Build.VERSION.SDK_INT>=29)arrayOf(Manifest.permission.CAMERA) else arrayOf(Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    private fun openGallery(withVoice: Boolean) {
        voice.stop();galleryWithVoice=withVoice
        if(LocalPhotoGallery.hasAccess(this))photoFlow.openGallery(withVoice)
        else mediaPermission.launch(LocalPhotoGallery.permissions())
    }
    private fun closePhotoFlow(){voice.stop();photoFlow.close()}
    private fun confirmGallery() {
        val uri=photoFlow.confirmation() ?: return
        closePhotoFlow();fieldModel.importPhoto(uri)
    }
    /** The same dispatcher handles microphone results and instrumentation-decoded phrases. */
    internal fun handleVoiceCommand(command: VoiceCommand) {
        if(!foreground || fieldModel.busy)return
        if(photoFlow.galleryOpen) {
            when(command.action) {
                VoiceAction.PHOTO_NUMBER->command.value.toIntOrNull()?.let(photoFlow::select)
                VoiceAction.FIRST_PHOTO->photoFlow.select(1)
                VoiceAction.LAST_PHOTO->photoFlow.select(photoFlow.photos.size)
                VoiceAction.NEXT_PHOTO->photoFlow.select((photoFlow.selectedNumber ?: 0)+1)
                VoiceAction.PREVIOUS_PHOTO->photoFlow.select((photoFlow.selectedNumber ?: 2)-1)
                VoiceAction.CONFIRM->confirmGallery()
                VoiceAction.CANCEL->closePhotoFlow()
                else->photoFlow.error("Назови номер фото или скажи «подтвердить» / «отмена».")
            }
            return
        }
        when(command.action) {
            VoiceAction.CAMERA->openCamera(true)
            VoiceAction.GALLERY->openGallery(true)
            VoiceAction.ANALYZE->if(fieldModel.bitmap!=null)fieldModel.analyze() else fieldModel.showError("Сначала сделай фото или выбери снимок")
            VoiceAction.MODEL->fieldModel.setModel(command.value)
            VoiceAction.CROP->fieldModel.selectCrop(command.value)
            VoiceAction.CANCEL->closePhotoFlow()
            else->fieldModel.showError("Сначала открой галерею, чтобы выбрать фото по номеру.")
        }
    }
    private fun voiceButton() {
        if(voice.active || photoFlow.dialogVoice){photoFlow.dialogVoice=false;voice.stop();return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)microphone.launch(Manifest.permission.RECORD_AUDIO)
        else if(photoFlow.galleryOpen)photoFlow.dialogVoice=true else voice.start()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voice=OfflineVoiceController(applicationContext)
        photoFlow=ViewModelProvider(this)[PhotoFlowViewModel::class.java]
        fieldModel=ViewModelProvider(this)[FieldDemoViewModel::class.java]
        voice.onCommand=::handleVoiceCommand
        setContent {
            AgroVoiceAITheme {
                val model=fieldModel
                val gallery=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null)model.importPhoto(uri) }
                val systemPicker: ()->Unit={closePhotoFlow();gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
                BackHandler(photoFlow.galleryOpen || photoFlow.cameraOpen){closePhotoFlow()}
                LaunchedEffect(photoFlow.galleryOpen,photoFlow.dialogVoice,photoFlow.loading,voice.active,foreground) {
                    if(foreground && photoFlow.galleryOpen && photoFlow.dialogVoice && !photoFlow.loading && !voice.active && photoFlow.photos.isNotEmpty()) {
                        delay(600)
                        voice.start(VoiceCommands.galleryGrammar(photoFlow.photos.size))
                    }
                }
                LaunchedEffect(photoFlow.galleryOpen,photoFlow.dialogVoice) {
                    if(photoFlow.galleryOpen && photoFlow.dialogVoice) {
                        delay(120000);photoFlow.dialogVoice=false;voice.stop()
                        photoFlow.error("Голосовой выбор остановлен через 2 минуты. Нажми «Голосовая команда», чтобы продолжить.")
                    }
                }
                Scaffold { padding ->
                    Box(Modifier.padding(padding)) {
                        when {
                            photoFlow.cameraOpen->PlantCamera(photoFlow,onSaved={uri->
                                if(foreground && photoFlow.cameraOpen){closePhotoFlow();model.importPhoto(uri)}
                            },onCancel=::closePhotoFlow)
                            photoFlow.galleryOpen->NumberedGallery(photoFlow,::confirmGallery,::closePhotoFlow,systemPicker) {
                                VoiceCommandPanel(voice,::voiceButton,true,gallery=true,dialog=photoFlow.dialogVoice)
                            }
                            else->FieldDemoScreen(model,Modifier,onGallery={openGallery(false)},onCamera={openCamera(false)},voice=voice,onVoice=::voiceButton,onSystemGallery=systemPicker)
                        }
                    }
                }
            }
        }
    }
    override fun onResume(){super.onResume();foreground=true}
    override fun onStop(){foreground=false;voice.stop();photoFlow.dialogVoice=false;if(photoFlow.cameraOpen)photoFlow.close();super.onStop()}
    override fun onDestroy(){voice.close();super.onDestroy()}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldDemoScreen(model: FieldDemoViewModel, modifier: Modifier,
                            onGallery: ()->Unit, onCamera: ()->Unit,
                            voice: OfflineVoiceController, onVoice: ()->Unit,onSystemGallery: ()->Unit) {
    val controlsEnabled=!model.busy && !voice.active
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("AgroVoice AI",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Проверка листьев · без интернета",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        VoiceCommandPanel(voice,onVoice,enabled=!model.busy)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onCamera,enabled=controlsEnabled,modifier=Modifier.weight(1f)){Text("Сделать фото")}
            OutlinedButton(onClick=onGallery,enabled=controlsEnabled,modifier=Modifier.weight(1f)){Text("Из галереи")}
        }
        TextButton(onClick=onSystemGallery,enabled=controlsEnabled){Text("Системный выбор фото")}
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
private fun VoiceCommandPanel(voice: OfflineVoiceController, onVoice: ()->Unit, enabled: Boolean,gallery: Boolean=false,dialog: Boolean=false) {
    var help by remember {mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(onClick=onVoice,enabled=enabled || voice.active,modifier=Modifier.weight(1f)) {
                    Text(if(voice.active || dialog)"Остановить микрофон" else "Голосовая команда")
                }
                TextButton(onClick={help=true}){Text("Фразы")}
            }
            Text(voice.status,style=MaterialTheme.typography.bodyMedium)
            if(voice.heard.isNotBlank())Text("Услышал: ${voice.heard}",style=MaterialTheme.typography.bodySmall)
            Text(if(gallery)"Скажи «фото номер три», проверь снимок и скажи «подтвердить»." else "Нажми и скажи, например: «сделай фото».",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(help)AlertDialog(onDismissRequest={help=false},title={Text("Голосовые команды")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Сделай фото — сразу снять и проверить\nОткрой галерею — фото с номерами\nФото номер три — показать снимок\nПервое фото — самое новое\nПоследнее фото — самое старое\nПодтвердить — выбрать показанное фото\nПроверь фото — повторить анализ")
            Text("Быстрая модель — YOLO26n\nБольшая модель — YOLO26s\nСтарая модель — YOLO11n")
            Text("Картофель · Томаты · Огурцы · Все культуры — выбрать культуру\nСтоп — отменить команду")
            Text("Говори после надписи «Слушаю». В галерее голосовой диалог продолжается до подтверждения, отмены или 2 минут. Вне галереи — одна команда. Уход в фон выключает микрофон. Аудио не сохраняется.")
        }},confirmButton={TextButton(onClick={help=false}){Text("Понятно")}})
}
