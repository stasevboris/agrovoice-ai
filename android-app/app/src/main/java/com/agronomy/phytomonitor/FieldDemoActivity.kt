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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.agronomy.phytomonitor.photos.*
import com.agronomy.phytomonitor.ui.theme.AgroVoiceAITheme
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.OfflineResultSpeaker
import kotlinx.coroutines.delay

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
                val exportJournal: ()->Unit={voice.stop();export.launch("AgroVoice-journal-${System.currentTimeMillis()}.json")}
                // Confirmations of finished actions («сохранено», «снято») appear for a moment; errors stay as banners.
                val snackbar=remember {SnackbarHostState()}
                val notice=model.message?.takeIf {!model.messageIsError}
                LaunchedEffect(notice) {
                    if(notice!=null){snackbar.showSnackbar(notice,withDismissAction=false,duration=SnackbarDuration.Short);model.clearMessage()}
                }
                Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snackbar)}) { padding ->
                    Box(Modifier.padding(padding)) {
                        when {
                            photoFlow.cameraOpen->PlantCamera(photoFlow,onSaved={uri->
                                if(foreground && photoFlow.cameraOpen){closePhotoFlow();model.importPhoto(uri)}
                            },onCancel=::closePhotoFlow) {VoiceStrip(voice,photoFlow,::voiceButton,::setContinuous,hint="Наведи камеру → «подтвердить».",onDark=true)}
                            photoFlow.galleryOpen->NumberedGallery(photoFlow,::confirmGallery,::closePhotoFlow,systemPicker) {
                                VoiceStrip(voice,photoFlow,::voiceButton,::setContinuous,hint="Номер → просмотр → «подтвердить».")
                            }
                            model.journalOpen->JournalScreen(model,onBack={model.journalOpen=false},onExport=exportJournal)
                            else->FieldDemoScreen(model,Modifier,onGallery={openGallery(false)},onCamera={openCamera(false)},voice=voice,flow=photoFlow,onVoice=::voiceButton,onContinuous=::setContinuous,onNote={openNote()},onSpeak={readResult()},speaking=speaker.active,speakerStatus=speaker.status)
                        }
                        InspectionDialogs(model,::dictateNote,::closeNote)
                    }
                }
            }
        }
    }
    override fun onResume(){super.onResume();foreground=true}
    override fun onStop(){foreground=false;voice.stop();speaker.stop();fieldModel.dictating=false;fieldModel.noteReviewVoice=false;photoFlow.dialogVoice=false;if(photoFlow.cameraOpen)photoFlow.close();super.onStop()}
    override fun onDestroy(){voice.close();speaker.close();super.onDestroy()}
}

