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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.platform.testTag
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
import com.agronomy.phytomonitor.voice.OfflineResultSpeaker
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min

class FieldDemoActivity : ComponentActivity() {
    private lateinit var voice: OfflineVoiceController
    private lateinit var speaker: OfflineResultSpeaker
    internal val voiceListening: Boolean get()=voice.active
    internal val voiceController: OfflineVoiceController get()=voice
    internal val resultSpeaker: OfflineResultSpeaker get()=speaker
    internal lateinit var photoFlow: PhotoFlowViewModel; private set
    internal lateinit var fieldModel: FieldDemoViewModel; private set
    private var foreground by mutableStateOf(false)
    private var galleryWithVoice=false
    private var automaticCamera=false
    private var cameraWithVoice=false
    private var requestContinuous=false
    private var requestDictation=false
    private var systemGallery: ()->Unit={}
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(granted && foreground) {
            if(requestDictation && fieldModel.noteOpen){requestDictation=false;fieldModel.dictating=true}
            else if(requestContinuous)photoFlow.setContinuous(true) else startOneShot()
        } else if(!granted){photoFlow.setContinuous(false);voice.permissionDenied()}
    }
    private val mediaPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(LocalPhotoGallery.hasAccess(this))photoFlow.openGallery(galleryWithVoice)
        else fieldModel.showError("Разреши доступ к фотографиям для галереи с номерами. Можно использовать системный выбор фото.")
    }
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(hasCameraPermission()){photoFlow.openCamera(automaticCamera);photoFlow.dialogVoice=cameraWithVoice && !automaticCamera}
        else fieldModel.showError("Разреши доступ к камере, чтобы сделать фото.")
    }
    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT>=29 || ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED)
    private fun openCamera(automatic: Boolean, withVoice: Boolean=false) {
        voice.stop();automaticCamera=automatic;cameraWithVoice=withVoice
        if(photoFlow.cameraOpen){if(automatic)photoFlow.requestShot();return}
        if(hasCameraPermission()){photoFlow.openCamera(automatic);photoFlow.dialogVoice=withVoice && !automatic}
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
        if(!foreground)return
        when(command.action) {
            VoiceAction.LISTEN_ON->{setContinuous(true);return}
            VoiceAction.LISTEN_OFF->{setContinuous(false);return}
            VoiceAction.HELP->{photoFlow.helpOpen=true;return}
            VoiceAction.CLOSE_HELP->{photoFlow.helpOpen=false;return}
            VoiceAction.EXIT->{setContinuous(false);finish();return}
            VoiceAction.BACK,VoiceAction.CANCEL->{
                if(fieldModel.noteOpen)closeNote() else if(fieldModel.journalOpen)fieldModel.journalOpen=false else if(photoFlow.helpOpen)photoFlow.helpOpen=false else closePhotoFlow()
                return
            }
            VoiceAction.SILENCE->{speaker.stop();return}
            VoiceAction.SCROLL->{photoFlow.scroll(command.value);return}
            else->Unit
        }
        if(fieldModel.busy){fieldModel.showError("Дождись окончания анализа фото.");return}
        if(fieldModel.noteOpen && command.action==VoiceAction.CONFIRM){closeNote();fieldModel.saveInspection();return}
        val mainScreen=!photoFlow.cameraOpen && !photoFlow.galleryOpen
        when(command.action) {
            VoiceAction.NOTE->{openNote(true);return}
            VoiceAction.JOURNAL->{fieldModel.openJournal();return}
            VoiceAction.SAVE_INSPECTION->{fieldModel.saveInspection();return}
            VoiceAction.ASSESS->{fieldModel.assess(command.value);return}
            VoiceAction.COMPARE->{fieldModel.compareModels();return}
            VoiceAction.SPEAK_RESULT->{readResult();return}
            VoiceAction.SHOW_BOXES->{fieldModel.showBoxes=true;return}
            VoiceAction.HIDE_BOXES->{fieldModel.showBoxes=false;return}
            VoiceAction.MODEL->{fieldModel.setModel(command.value,mainScreen);return}
            VoiceAction.CROP->{fieldModel.selectCrop(command.value,mainScreen);return}
            VoiceAction.THRESHOLD,VoiceAction.THRESHOLD_UP,VoiceAction.THRESHOLD_DOWN->{
                val value=when(command.action) {
                    VoiceAction.THRESHOLD->command.value.toInt()/100f
                    VoiceAction.THRESHOLD_UP->fieldModel.threshold+0.05f
                    else->fieldModel.threshold-0.05f
                }
                fieldModel.changeThreshold(value.coerceIn(0.1f,0.8f))
                if(mainScreen && fieldModel.bitmap!=null)fieldModel.analyze()
                return
            }
            VoiceAction.SYSTEM_GALLERY->{systemGallery();return}
            VoiceAction.GALLERY->{openGallery(true);return}
            VoiceAction.CAMERA,VoiceAction.OPEN_CAMERA->{openCamera(false,true);return}
            VoiceAction.CAMERA_NOW->{openCamera(true);return}
            else->Unit
        }
        if(photoFlow.cameraOpen) {
            if(command.action==VoiceAction.CONFIRM){voice.stop();photoFlow.requestShot()}
            else photoFlow.error("Наведи камеру и скажи «подтвердить» или «отмена».")
            return
        }
        if(photoFlow.galleryOpen) {
            when(command.action) {
                VoiceAction.PHOTO_NUMBER->command.value.toIntOrNull()?.let(photoFlow::select)
                VoiceAction.FIRST_PHOTO->photoFlow.select(1)
                VoiceAction.LAST_PHOTO->photoFlow.select(photoFlow.photos.size)
                VoiceAction.NEXT_PHOTO->photoFlow.select((photoFlow.selectedNumber ?: 0)+1)
                VoiceAction.PREVIOUS_PHOTO->photoFlow.select((photoFlow.selectedNumber ?: 2)-1)
                VoiceAction.CONFIRM->confirmGallery()
                else->photoFlow.error("Назови номер фото или скажи «подтвердить» / «отмена».")
            }
            return
        }
        when(command.action) {
            VoiceAction.ANALYZE->if(fieldModel.bitmap!=null)fieldModel.analyze() else fieldModel.showError("Сначала сделай фото или выбери снимок")
            else->fieldModel.showError("Сначала открой галерею, чтобы выбрать фото по номеру.")
        }
    }
    private fun voiceButton() {
        if(voice.active || photoFlow.dialogVoice || photoFlow.continuousVoice){setContinuous(false);return}
        requestContinuous=false
        if(!hasMicrophone())microphone.launch(Manifest.permission.RECORD_AUDIO) else startOneShot()
    }
    private fun hasMicrophone()=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun currentGrammar()=when {
        fieldModel.noteOpen->if(fieldModel.dictating)null else VoiceCommands.noteReviewGrammar
        photoFlow.cameraOpen->VoiceCommands.cameraGrammar
        photoFlow.galleryOpen->VoiceCommands.galleryGrammar(photoFlow.photos.size)
        else->VoiceCommands.grammar
    }
    private fun currentScreen()=when {fieldModel.noteOpen->if(fieldModel.dictating)"note" else "note_review";photoFlow.cameraOpen->"camera";photoFlow.galleryOpen->"gallery";else->"main"}
    private fun openNote(dictate: Boolean=false){voice.stop();speaker.stop();fieldModel.noteOpen=true;fieldModel.noteReviewVoice=false;fieldModel.dictating=dictate && hasMicrophone();if(dictate && !hasMicrophone()){requestDictation=true;microphone.launch(Manifest.permission.RECORD_AUDIO)}}
    private fun closeNote(){voice.stop();fieldModel.dictating=false;fieldModel.noteReviewVoice=false;fieldModel.noteOpen=false}
    private fun dictateNote(){
        voice.stop()
        if(fieldModel.dictating){fieldModel.dictating=false;fieldModel.noteReviewVoice=true;return}
        fieldModel.noteReviewVoice=false
        if(hasMicrophone())fieldModel.dictating=true else {requestDictation=true;microphone.launch(Manifest.permission.RECORD_AUDIO)}
    }
    internal fun readResult(volume: Float=1f){
        if(speaker.active){speaker.stop();return}
        voice.stop()
        if(!speaker.speak(fieldModel.spokenResult(),volume))fieldModel.showError(speaker.status)
    }
    private fun startOneShot(){if(photoFlow.galleryOpen)photoFlow.dialogVoice=true else voice.start(currentGrammar(),screen=currentScreen())}
    internal fun setContinuous(enabled: Boolean) {
        requestContinuous=enabled
        photoFlow.dialogVoice=false;voice.stop()
        if(enabled && !hasMicrophone())microphone.launch(Manifest.permission.RECORD_AUDIO)
        else photoFlow.setContinuous(enabled)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voice=OfflineVoiceController(applicationContext)
        speaker=OfflineResultSpeaker(applicationContext)
        photoFlow=ViewModelProvider(this)[PhotoFlowViewModel::class.java]
        fieldModel=ViewModelProvider(this)[FieldDemoViewModel::class.java]
        voice.onCommand=::handleVoiceCommand
        voice.onFailure={photoFlow.setContinuous(false);photoFlow.dialogVoice=false}
        voice.onDictation={text->fieldModel.editNote((fieldModel.noteDraft+" "+text).trim())}
        voice.onDictationFinished={fieldModel.dictating=false;fieldModel.noteReviewVoice=true}
        setContent {
            AgroVoiceAITheme {
                val model=fieldModel
                val gallery=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null)model.importPhoto(uri) }
                val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)model.exportJournal(uri)}
                val systemPicker: ()->Unit={closePhotoFlow();gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
                SideEffect {systemGallery=systemPicker}
                BackHandler(model.noteOpen || model.journalOpen || photoFlow.galleryOpen || photoFlow.cameraOpen || photoFlow.helpOpen){if(model.noteOpen)closeNote() else if(model.journalOpen)model.journalOpen=false else if(photoFlow.helpOpen)photoFlow.helpOpen=false else closePhotoFlow()}
                val context=currentScreen()
                val eligible=foreground && !speaker.active && !photoFlow.loading && !photoFlow.shotRequested && !photoFlow.cameraCapturing
                LaunchedEffect(context,model.dictating,model.noteReviewVoice,photoFlow.continuousVoice,photoFlow.dialogVoice,eligible,voice.active) {
                    if(eligible && !voice.active && hasMicrophone() && (if(model.noteOpen)model.dictating || model.noteReviewVoice else photoFlow.continuousVoice || photoFlow.dialogVoice)) {
                        delay(400)
                        voice.start(currentGrammar(),continuous=model.dictating || photoFlow.continuousVoice,screen=context)
                    }
                }
                LaunchedEffect(model.noteOpen,model.noteReviewVoice){
                    if(model.noteOpen && model.noteReviewVoice){delay(120000);model.noteReviewVoice=false;voice.stop()}
                }
                LaunchedEffect(context,photoFlow.dialogVoice,photoFlow.continuousVoice) {
                    if(photoFlow.dialogVoice && !photoFlow.continuousVoice) {
                        delay(120000);photoFlow.dialogVoice=false;voice.stop()
                        photoFlow.error("Голосовой диалог остановлен через 2 минуты. Включи постоянное слушание, чтобы продолжить без ограничения.")
                    }
                }
                Scaffold { padding ->
                    Box(Modifier.padding(padding)) {
                        when {
                            photoFlow.cameraOpen->PlantCamera(photoFlow,onSaved={uri->
                                if(foreground && photoFlow.cameraOpen){closePhotoFlow();model.importPhoto(uri)}
                            },onCancel=::closePhotoFlow) {VoiceCommandPanel(voice,photoFlow,::voiceButton,::setContinuous,context="camera")}
                            photoFlow.galleryOpen->NumberedGallery(photoFlow,::confirmGallery,::closePhotoFlow,systemPicker) {
                                VoiceCommandPanel(voice,photoFlow,::voiceButton,::setContinuous,context="gallery")
                            }
                            else->FieldDemoScreen(model,Modifier,onGallery={openGallery(false)},onCamera={openCamera(false)},voice=voice,flow=photoFlow,onVoice=::voiceButton,onContinuous=::setContinuous,onSystemGallery=systemPicker,onNote={openNote()},onSpeak={readResult()},speaking=speaker.active,speakerStatus=speaker.status)
                        }
                        InspectionDialogs(model,::dictateNote,::closeNote){voice.stop();export.launch("AgroVoice-journal-${System.currentTimeMillis()}.json")}
                    }
                }
            }
        }
    }
    override fun onResume(){super.onResume();foreground=true}
    override fun onStop(){foreground=false;voice.stop();speaker.stop();fieldModel.dictating=false;fieldModel.noteReviewVoice=false;photoFlow.dialogVoice=false;if(photoFlow.cameraOpen)photoFlow.close();super.onStop()}
    override fun onDestroy(){voice.close();speaker.close();super.onDestroy()}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldDemoScreen(model: FieldDemoViewModel, modifier: Modifier,
                            onGallery: ()->Unit, onCamera: ()->Unit,
                            voice: OfflineVoiceController, flow: PhotoFlowViewModel,onVoice: ()->Unit,onContinuous: (Boolean)->Unit,onSystemGallery: ()->Unit,onNote: ()->Unit,onSpeak: ()->Unit,speaking: Boolean,speakerStatus: String) {
    val controlsEnabled=!model.busy
    val scroll=rememberScrollState()
    var handledScroll by remember {mutableIntStateOf(flow.scrollEvent)}
    LaunchedEffect(flow.scrollEvent) {
        if(handledScroll!=flow.scrollEvent){handledScroll=flow.scrollEvent
            when(flow.scrollDirection){"top"->scroll.animateScrollTo(0);"bottom"->scroll.animateScrollTo(scroll.maxValue);else->scroll.animateScrollTo((scroll.value+if(flow.scrollDirection=="down")600 else -600).coerceIn(0,scroll.maxValue))}
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("AgroVoice AI",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Проверка листьев · без интернета",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        VoiceCommandPanel(voice,flow,onVoice,onContinuous)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onCamera,enabled=controlsEnabled,modifier=Modifier.weight(1f)){Text("Сделать фото")}
            OutlinedButton(onClick=onGallery,enabled=controlsEnabled,modifier=Modifier.weight(1f)){Text("Из галереи")}
        }
        TextButton(onClick=onSystemGallery,enabled=controlsEnabled){Text("Системный выбор фото")}
        Text("Модель",style=MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            model.detector.models.forEach { version ->
                FilterChip(selected=model.selectedModel==version.id,onClick={model.setModel(version.id)},label={Text("${VoiceCommands.modelIds.indexOf(version.id)+1}. ${version.name}")},enabled=controlsEnabled)
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
                        model.result?.boxes?.takeIf {model.showBoxes}?.forEachIndexed { index,box ->
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
        InspectionTools(model,onNote,onSpeak,speaking,speakerStatus)
        Text("Предварительные модели. Результат нужно проверять: уверенность не означает вероятность болезни или процент повреждения.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Огурец: модель пока определяет лист без диагноза болезни.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VoiceCommandPanel(voice: OfflineVoiceController, flow: PhotoFlowViewModel, onVoice: ()->Unit, onContinuous: (Boolean)->Unit, context: String="main") {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            // The whole row toggles: a large target for gloves, and TalkBack reads the label with the switch.
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value=flow.continuousVoice,role=Role.Switch,onValueChange=onContinuous)
                    .testTag("continuous_voice"),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Text("Постоянно слушать",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(checked=flow.continuousVoice,onCheckedChange=null)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(onClick=onVoice,modifier=Modifier.weight(1f)) {
                    Text(if(voice.active || flow.dialogVoice || flow.continuousVoice)"Остановить микрофон" else "Голосовая команда")
                }
                TextButton(onClick={flow.helpOpen=true}){Text("Фразы")}
            }
            Text(voice.status,style=MaterialTheme.typography.bodySmall)
            if(voice.heard.isNotBlank())Text("Услышал: ${voice.heard}",style=MaterialTheme.typography.bodySmall)
            Text(when(context){"camera"->"Наведи камеру → «подтвердить».";"gallery"->"Номер → просмотр → «подтвердить».";else->"«Сделай фото» или «открой галерею»."},style=MaterialTheme.typography.bodySmall)
        }
    }
    if(flow.helpOpen)AlertDialog(onDismissRequest={flow.helpOpen=false},title={Text("Голосовые команды")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Сделай фото — камера, наведи и скажи «подтвердить»\nСделай фото сразу — сразу снять\nОткрой галерею — фото с номерами\nФото номер три / три — показать снимок\nСледующее фото / предыдущее фото — листать\nПервое фото — самое новое; последнее — самое старое\nПодтвердить — снять в камере или выбрать в галерее\nОтмена / назад — вернуться\nПроверь фото — анализ выбранного снимка")
            Text("Модель один — YOLO11n\nМодель два — YOLO26n (быстрая модель)\nМодель три — MobileNet\nМодель четыре — EfficientNet\nМодель пять — маска + MobileNet\nМодель шесть — ученик\nМодель семь — YOLO26s (большая модель)\nМодель восемь — DETR, пример\nМодель девять — YOLO26m, технический пилот")
            Text("Добавь заметку — диктовка к снимку\nЗакончить заметку / стоп — остановить диктовку, затем «подтвердить» — сохранить\nСохрани осмотр — запись в журнал\nОткрой журнал / покажи историю\nРезультат верный / результат неверный / не уверен — твоя оценка\nСравни модели — один снимок и одинаковые настройки\nПрочитай результат — автономный русский голос, если установлен\nПокажи рамки / убери рамки")
            Text("Картофель · Томаты · Огурцы · Все культуры\nПорог двадцать пять [процентов] — от10 до80\nУвеличь порог / уменьши порог — шаг5\nПрокрути вниз / вверх · в начало / в конец\nПомощь / покажи команды · понятно — закрыть подсказку\nСтоп / выключи голос — выключить слушание\nСлушай постоянно — включить постоянный режим\nЗакрой приложение — выйти")
            Text("Переключатель слушает непрерывно, пока приложение на экране, до выключения. В фоне микрофон выключен; при возвращении включится, если переключатель остался включён. Без переключателя — одна команда; в камере/галерее диалог до2 минут. В системном выборе фото голосом управлять нельзя: используй галерею с номерами. Аудио не сохраняется.")
        }},confirmButton={TextButton(onClick={flow.helpOpen=false}){Text("Понятно")}})
}
