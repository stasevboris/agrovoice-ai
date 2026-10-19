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
import com.agronomy.phytomonitor.actions.ActionDispatcher
import com.agronomy.phytomonitor.actions.ActionPort
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AppState
import com.agronomy.phytomonitor.actions.AssistantPlan
import com.agronomy.phytomonitor.actions.FollowUpDialog
import com.agronomy.phytomonitor.actions.Verdict
import com.agronomy.phytomonitor.actions.Operation
import com.agronomy.phytomonitor.actions.Screen
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
    /** Microphone results and instrumentation-decoded phrases go through the same executor as buttons. */
    internal fun handleVoiceCommand(command: VoiceCommand) {
        if (!foreground) {
            return
        }
        val request = ActionRequest(command.action, command.value, ActionSource.VOICE)
        val verdict = actions.dispatch(request)
        afterVoiceCommand(request, verdict)
    }

    /** V-02: a photo or check asked by voice is answered aloud; the answer to the question is a command too. */
    private fun afterVoiceCommand(request: ActionRequest, verdict: Verdict) {
        if (followUp.phase == FollowUpDialog.Phase.FOLLOW_UP) {
            followUp.commandHandled(request, verdict)?.let(::sayFollowUp)
        } else if (request.action in setOf(VoiceAction.CANCEL, VoiceAction.BACK, VoiceAction.LISTEN_OFF, VoiceAction.EXIT)) {
            followUp.stop()
        } else if (verdict is Verdict.Run && verdict.operations.any { it in startsAnalysis || it is Operation.OpenCamera }) {
            followUp.voiceAnalysisRequested()
        }
        followUpPhase = followUp.phase
    }

    private val startsAnalysis = setOf(Operation.TakeShot, Operation.ConfirmGallery, Operation.Analyze)

    private fun sayFollowUp(prompt: FollowUpDialog.Prompt) {
        val text = if (prompt == FollowUpDialog.Prompt.RESULT_AND_QUESTION) {
            fieldModel.spokenResult() + " " + FollowUpDialog.QUESTION
        } else {
            FollowUpDialog.text(prompt)
        }
        speak(text)
    }

    /** A button of the screen: the same checks and the same operations as the spoken command. */
    private fun press(action: VoiceAction, value: String = "") {
        if (!foreground) {
            return
        }
        // A tap means the hands are free again: the spoken question is no longer needed.
        followUp.stop()
        followUpPhase = followUp.phase
        actions.dispatch(ActionRequest(action, value, ActionSource.BUTTON))
    }

    /**
     * Entry point for a future on-device router: one answer in the JSON protocol of
     * ml-pipeline/assistant. No model produces it in the app yet.
     */
    internal fun handleAssistantAnswer(json: String, utterance: String): AssistantPlan? {
        if (!foreground) {
            return null
        }
        return actions.submit(json, utterance)
    }

    internal fun currentState(): AppState = AppState(
        screen = currentScreen(),
        hasPhoto = fieldModel.bitmap != null,
        hasResult = fieldModel.reportName != null,
        busy = fieldModel.busy,
        inspectionSaved = fieldModel.inspectionSaved,
        review = fieldModel.review,
        galleryCount = if (photoFlow.loading) 0 else photoFlow.photos.size,
        selectedPhoto = photoFlow.selectedNumber,
        cameraCapturing = photoFlow.shotRequested || photoFlow.cameraCapturing,
        speaking = speaker.active,
        helpOpen = photoFlow.helpOpen,
        threshold = fieldModel.threshold,
        selectedModel = fieldModel.selectedModel,
        crop = fieldModel.crop,
        crops = fieldModel.currentModel().crops,
        availableModels = fieldModel.detector.models.map { it.id },
        plots = fieldModel.plots.map { it.plot.name },
        plot = fieldModel.plotName,
        row = fieldModel.rowName,
        inspectionOpen = fieldModel.openedInspection != null,
        followUp = followUp.awaitingAnswer
    )

    private val actionPort = object : ActionPort {
        override fun state() = currentState()

        override fun perform(operation: Operation) {
            when (operation) {
                is Operation.SetContinuousListening -> {
                    if (!operation.enabled) {
                        fieldModel.noteReviewVoice = false
                    }
                    setContinuous(operation.enabled)
                }
                is Operation.ShowHelp -> photoFlow.helpOpen = operation.visible
                Operation.Exit -> finish()
                Operation.CloseNote -> closeNote()
                Operation.CloseJournal -> fieldModel.journalOpen = false
                Operation.CloseInspection -> fieldModel.closeInspection()
                // The dialog itself answers «Не сохраняю» after the verdict.
                Operation.EndFollowUp -> Unit
                Operation.ClosePhotoFlow -> closePhotoFlow()
                Operation.StopSpeech -> speaker.stop()
                is Operation.Scroll -> photoFlow.scroll(operation.direction)
                is Operation.OpenNote -> openNote(operation.dictate)
                Operation.OpenJournal -> fieldModel.openJournal()
                Operation.OpenPlots -> fieldModel.openPlots()
                Operation.ClosePlots -> fieldModel.plotsOpen = false
                is Operation.SelectPlot -> fieldModel.editPlot(operation.name)
                is Operation.SetRow -> fieldModel.editRow(operation.row)
                Operation.SaveInspection -> fieldModel.saveInspection()
                is Operation.Assess -> fieldModel.assess(operation.review)
                Operation.CompareModels -> fieldModel.compareModels()
                Operation.SpeakResult -> readResult()
                is Operation.ShowBoxes -> fieldModel.showBoxes = operation.visible
                is Operation.SelectModel -> fieldModel.setModel(operation.id, operation.analyze)
                is Operation.SelectCrop -> fieldModel.selectCrop(operation.id, operation.analyze)
                is Operation.SetThreshold -> {
                    fieldModel.changeThreshold(operation.value)
                    if (operation.analyze) {
                        fieldModel.analyze()
                    }
                }
                Operation.SystemGallery -> systemGallery()
                is Operation.OpenGallery -> openGallery(operation.withVoice)
                is Operation.OpenCamera -> openCamera(operation.immediate, operation.withVoice)
                Operation.TakeShot -> {
                    voice.stop()
                    photoFlow.requestShot()
                }
                is Operation.SelectPhoto -> photoFlow.select(operation.number)
                Operation.ConfirmGallery -> confirmGallery()
                Operation.Analyze -> fieldModel.analyze()
            }
        }

        override fun tell(text: String, isError: Boolean, request: ActionRequest?) {
            if (photoFlow.cameraOpen || photoFlow.galleryOpen) {
                photoFlow.error(text)
            } else if (isError) {
                fieldModel.showError(text)
            } else {
                fieldModel.notify(text)
            }
            // «Прочитай результат» without a result answers aloud, as the button did before.
            if (isError && request?.action == VoiceAction.SPEAK_RESULT) {
                speak(text)
            }
        }
    }
    internal val actions = ActionDispatcher(actionPort)
    private val followUp = FollowUpDialog()
    /** Compose-visible copy of the dialog phase: it decides whether to listen after speech. */
    private var followUpPhase by mutableStateOf(FollowUpDialog.Phase.IDLE)
    private fun voiceButton() {
        if(voice.active || photoFlow.dialogVoice || photoFlow.continuousVoice){setContinuous(false);return}
        requestContinuous=false
        if(!hasMicrophone())microphone.launch(Manifest.permission.RECORD_AUDIO) else startOneShot()
    }
    private fun hasMicrophone()=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun currentGrammar()=when {
        fieldModel.noteOpen->if(fieldModel.dictating)null else VoiceCommands.noteReviewGrammar
        // Free speech: the whole Russian vocabulary of the model, understood by FreeSpeech rules.
        photoFlow.freeSpeech->null
        photoFlow.cameraOpen->VoiceCommands.cameraGrammar
        photoFlow.galleryOpen->VoiceCommands.galleryGrammar(photoFlow.photos.size)
        else->VoiceCommands.mainGrammar(fieldModel.plots.map {it.plot.name},followUp=followUp.awaitingAnswer)
    }
    private fun currentScreen(): Screen = when {
        fieldModel.noteOpen -> if (fieldModel.dictating) Screen.NOTE else Screen.NOTE_REVIEW
        photoFlow.cameraOpen -> Screen.CAMERA
        photoFlow.galleryOpen -> Screen.GALLERY
        fieldModel.journalOpen -> Screen.JOURNAL
        fieldModel.plotsOpen -> Screen.PLOTS
        else -> Screen.MAIN
    }
    private fun openNote(dictate: Boolean=false){voice.stop();speaker.stop();fieldModel.noteOpen=true;fieldModel.noteReviewVoice=false;fieldModel.dictating=dictate && hasMicrophone();if(dictate && !hasMicrophone()){requestDictation=true;microphone.launch(Manifest.permission.RECORD_AUDIO)}}
    private fun closeNote(){voice.stop();fieldModel.dictating=false;fieldModel.noteReviewVoice=false;fieldModel.noteOpen=false}
    private fun dictateNote(){
        voice.stop()
        if(fieldModel.dictating){fieldModel.dictating=false;fieldModel.noteReviewVoice=true;return}
        fieldModel.noteReviewVoice=false
        if(hasMicrophone())fieldModel.dictating=true else {requestDictation=true;microphone.launch(Manifest.permission.RECORD_AUDIO)}
    }
    internal fun readResult(volume: Float=1f){
        speak(fieldModel.spokenResult(),volume)
    }
    private fun speak(text: String, volume: Float=1f){
        voice.stop()
        if(!speaker.speak(text,volume))fieldModel.showError(speaker.status)
    }
    private fun startOneShot(){
        if(photoFlow.galleryOpen){photoFlow.dialogVoice=true;return}
        voice.plotNames=fieldModel.plots.map {it.plot.name}
        voice.freeSpeech=photoFlow.freeSpeech
        voice.start(currentGrammar(),screen=currentScreen().protocolName)
    }
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(fieldModel.location.hasPermission()){fieldModel.switchLocation(true);startLocation()}
        else fieldModel.showError("Без разрешения на геолокацию координаты к осмотру не прикрепляются. Остальное работает.")
    }
    /** The switch next to the plot: asks for permission once, then follows the screen lifecycle. */
    private fun toggleLocation(enabled: Boolean){
        if(!enabled){fieldModel.switchLocation(false);return}
        if(fieldModel.location.hasPermission()){fieldModel.switchLocation(true);startLocation()}
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun startLocation(){
        if(fieldModel.locationEnabled && !fieldModel.location.start())
            fieldModel.showError("Геолокация выключена в настройках телефона: включите её, чтобы записывать координаты.")
    }
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
        voice.onCommands={commands->
            if(foreground){
                followUp.stop()
                followUpPhase=followUp.phase
                actions.submitPlan(commands.map {ActionRequest(it.action,it.value,ActionSource.VOICE)})
            }
        }
        voice.onFailure={photoFlow.setContinuous(false);photoFlow.dialogVoice=false}
        voice.onDictation={text->fieldModel.editNote((fieldModel.noteDraft+" "+text).trim())}
        voice.onDictationFinished={fieldModel.dictating=false;fieldModel.noteReviewVoice=true}
        setContent {
            AgroVoiceAITheme {
                val model=fieldModel
                val gallery=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null)model.importPhoto(uri) }
                // One document type per format, so the file picker suggests the right extension.
                val exports=JournalExport.entries.associateWith {format->
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)){uri->if(uri!=null)model.exportJournal(uri,format)}
                }
                val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)model.importJournal(uri)}
                val systemPicker: ()->Unit={closePhotoFlow();gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
                SideEffect {systemGallery=systemPicker}
                BackHandler(model.noteOpen || model.journalOpen || model.plotsOpen || photoFlow.galleryOpen || photoFlow.cameraOpen || photoFlow.helpOpen){press(VoiceAction.BACK)}
                val context=currentScreen().protocolName
                val eligible=foreground && !speaker.active && !photoFlow.loading && !photoFlow.shotRequested && !photoFlow.cameraCapturing
                LaunchedEffect(context,model.dictating,model.noteReviewVoice,photoFlow.continuousVoice,photoFlow.dialogVoice,eligible,voice.active,followUpPhase) {
                    val answering=!voice.active && followUp.shouldListenAgain()
                    followUpPhase=followUp.phase
                    if(eligible && !voice.active && hasMicrophone() && (if(model.noteOpen)model.dictating || model.noteReviewVoice else photoFlow.continuousVoice || photoFlow.dialogVoice || answering)) {
                        delay(400)
                        voice.plotNames=model.plots.map {it.plot.name}
                        voice.freeSpeech=photoFlow.freeSpeech
                        if(answering)followUp.listeningStarted()
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
                // A plan of several steps continues only after background work finished without an error.
                LaunchedEffect(model.busy) {
                    if(!model.busy){
                        val failed=model.message!=null && model.messageIsError
                        actions.settled(failed=failed)
                        val prompt=followUp.saveFinished(failed) ?: followUp.analysisFinished(hasResult=model.reportName!=null)
                        followUpPhase=followUp.phase
                        prompt?.let(::sayFollowUp)
                    }
                }
                val exportJournal: (JournalExport)->Unit={format->
                    voice.stop()
                    exports.getValue(format).launch("AgroVoice-journal-${System.currentTimeMillis()}.${format.extension}")
                }
                val importJournal: ()->Unit={
                    voice.stop()
                    restore.launch(arrayOf("application/zip","application/json","application/octet-stream"))
                }
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
                            },onCancel={press(VoiceAction.CANCEL)}) {VoiceStrip(voice,photoFlow,::voiceButton,::setContinuous,hint="Наведи камеру → «подтвердить».",onDark=true)}
                            photoFlow.galleryOpen->NumberedGallery(photoFlow,{press(VoiceAction.CONFIRM)},{press(VoiceAction.CANCEL)},systemPicker) {
                                VoiceStrip(voice,photoFlow,::voiceButton,::setContinuous,hint="Номер → просмотр → «подтвердить».")
                            }
                            model.plotsOpen->PlotsScreen(model,onBack={press(VoiceAction.BACK)},onSelect={name->press(VoiceAction.PLOT,name)})
                            model.journalOpen && model.openedInspection!=null->SavedInspectionScreen(model.openedInspection!!,onBack={press(VoiceAction.BACK)},onReanalyse=model::reanalyseOpened)
                            model.journalOpen->JournalScreen(model,onBack={press(VoiceAction.BACK)},onExport=exportJournal,onImport=importJournal)
                            else->FieldDemoScreen(model,Modifier,onAction=::press,voice=voice,flow=photoFlow,onVoice=::voiceButton,onContinuous=::setContinuous,speaking=speaker.active,speakerStatus=speaker.status,onLocation=::toggleLocation)
                        }
                        InspectionDialogs(model,::dictateNote,onConfirm={press(VoiceAction.CONFIRM)},onCloseNote={press(VoiceAction.CANCEL)})
                    }
                }
            }
        }
    }
    override fun onResume(){super.onResume();foreground=true;if(fieldModel.locationEnabled && fieldModel.location.hasPermission())startLocation()}
    override fun onStop(){foreground=false;actions.cancelPlan();followUp.stop();followUpPhase=followUp.phase;fieldModel.location.stop();voice.stop();speaker.stop();fieldModel.dictating=false;fieldModel.noteReviewVoice=false;photoFlow.dialogVoice=false;if(photoFlow.cameraOpen)photoFlow.close();super.onStop()}
    override fun onDestroy(){voice.close();speaker.close();super.onDestroy()}
}

