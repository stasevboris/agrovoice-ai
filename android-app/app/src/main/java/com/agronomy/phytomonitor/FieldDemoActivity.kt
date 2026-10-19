package com.agronomy.phytomonitor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import com.agronomy.phytomonitor.actions.PlotTarget
import com.agronomy.phytomonitor.photos.*
import com.agronomy.phytomonitor.ui.theme.AgroVoiceAITheme
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.OfflineResultSpeaker
import com.agronomy.phytomonitor.voice.HeadsetButton
import com.agronomy.phytomonitor.voice.HeadsetButtonPolicy
import com.agronomy.phytomonitor.voice.HeadsetPress
import com.agronomy.phytomonitor.voice.VoiceRouter
import kotlinx.coroutines.delay
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agronomy.phytomonitor.journal.JournalViewModel
import com.agronomy.phytomonitor.journal.PlotsViewModel
import java.io.File
import com.agronomy.phytomonitor.settings.SettingsScreen
import com.agronomy.phytomonitor.settings.SettingsViewModel
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.knowledge.KnowledgeViewModel
import com.agronomy.phytomonitor.knowledge.KnowledgeScreen
import com.agronomy.phytomonitor.ml.CropVocabulary

class FieldDemoActivity : ComponentActivity() {
    private lateinit var voice: OfflineVoiceController
    private lateinit var speaker: OfflineResultSpeaker
    private lateinit var headset: HeadsetButton
    internal val voiceListening: Boolean get()=voice.active
    internal val voiceController: OfflineVoiceController get()=voice
    internal val resultSpeaker: OfflineResultSpeaker get()=speaker
    internal lateinit var photoFlow: PhotoFlowViewModel; private set
    internal lateinit var cameraModel: CameraViewModel; private set
    internal lateinit var fieldModel: FieldDemoViewModel; private set
    internal lateinit var journalModel: JournalViewModel; private set
    internal lateinit var plotsModel: PlotsViewModel; private set
    internal lateinit var settingsModel: SettingsViewModel; private set
    internal lateinit var knowledgeModel: KnowledgeViewModel; private set
    private val settings by lazy { SettingsStore.get(application) }
    private var navigation: NavHostController? = null
    private val cameraOpen get() = navigation?.currentDestination?.route == "camera"
    private val galleryOpen get() = navigation?.currentDestination?.route == "gallery"
    private var foreground by mutableStateOf(false)
    private var galleryWithVoice=false
    private var automaticCamera=false
    private var cameraWithVoice=false
    private var requestContinuous=false
    private var requestDictation=false
    private var systemGallery: ()->Unit={}
    private var exportJournalFile: (JournalExport) -> Unit = {}
    private var importJournalFile: () -> Unit = {}
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(granted && foreground && settings.state.value?.voiceEnabled == true) {
            if(requestDictation && fieldModel.noteOpen){requestDictation=false;fieldModel.dictating=true}
            else if(requestContinuous)photoFlow.setContinuous(true) else startOneShot()
        } else if(!granted){photoFlow.setContinuous(false);voice.permissionDenied()}
    }
    private val mediaPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(foreground && LocalPhotoGallery.hasAccess(this))enterGallery(galleryWithVoice)
        else fieldModel.showError("Разреши доступ к фотографиям для галереи с номерами. Можно использовать системный выбор фото.")
    }
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(foreground && hasCameraPermission())enterCamera(automaticCamera, cameraWithVoice)
        else fieldModel.showError("Разреши доступ к камере, чтобы сделать фото.")
    }
    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT>=29 || ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED)
    private fun openCamera(automatic: Boolean, withVoice: Boolean=false) {
        voice.stop();automaticCamera=automatic;cameraWithVoice=withVoice
        if(cameraOpen){if(automatic)cameraModel.requestShot();return}
        if(hasCameraPermission())enterCamera(automatic, withVoice)
        else cameraPermission.launch(if(Build.VERSION.SDK_INT>=29)arrayOf(Manifest.permission.CAMERA) else arrayOf(Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    private fun openGallery(withVoice: Boolean) {
        voice.stop();galleryWithVoice=withVoice
        if(LocalPhotoGallery.hasAccess(this))enterGallery(withVoice)
        else mediaPermission.launch(LocalPhotoGallery.permissions())
    }
    private fun navigatePhoto(route: String) {
        if (cameraOpen || galleryOpen) navigation?.popBackStack()
        navigation?.navigate(route) { launchSingleTop = true }
    }
    private fun enterCamera(automatic: Boolean, withVoice: Boolean) {
        photoFlow.close()
        cameraModel.open(automatic)
        photoFlow.dialogVoice = withVoice && !automatic
        navigatePhoto("camera")
    }
    private fun enterGallery(withVoice: Boolean) {
        cameraModel.close()
        photoFlow.openGallery(withVoice)
        navigatePhoto("gallery")
    }
    private fun closePhotoFlow() {
        voice.stop()
        cameraModel.close()
        photoFlow.close()
        if (cameraOpen || galleryOpen) navigation?.popBackStack()
    }
    private fun confirmGallery() {
        val uri=photoFlow.confirmation() ?: return
        closePhotoFlow()
        navigation?.popBackStack("main", false)
        fieldModel.importPhoto(uri)
    }
    /** Microphone results and instrumentation-decoded phrases go through the same executor as buttons. */
    internal fun handleVoiceCommand(command: VoiceCommand) {
        voiceRouter.command(command)
    }

    /** V-02: a photo or check asked by voice is answered aloud; the answer to the question is a command too. */

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
        return voiceRouter.answer(json, utterance)
    }

    internal fun currentState(): AppState = AppState(
        screen = currentScreen(),
        hasPhoto = fieldModel.bitmap != null,
        hasResult = fieldModel.reportName != null,
        hasDraft = fieldModel.hasDraft,
        busy = fieldModel.busy || journalModel.state.value.busy || journalModel.state.value.openingId != null || plotsModel.state.value.busy,
        inspectionSaved = fieldModel.inspectionSaved,
        review = fieldModel.review,
        galleryCount = if (photoFlow.loading) 0 else photoFlow.photos.size,
        selectedPhoto = photoFlow.selectedNumber,
        cameraCapturing = cameraModel.state.value.shotRequested || cameraModel.state.value.capturing,
        speaking = speaker.active,
        helpOpen = photoFlow.helpOpen,
        threshold = fieldModel.threshold,
        selectedModel = fieldModel.selectedModel,
        crop = if (currentScreen() == Screen.KNOWLEDGE) knowledgeModel.state.value.crop else fieldModel.crop,
        crops = if (currentScreen() == Screen.KNOWLEDGE) CropVocabulary.names.keys.toList() else fieldModel.currentModel().crops,
        canRefineCrop = currentScreen() == Screen.KNOWLEDGE || fieldModel.currentModel().family != "detr",
        verifiedKnowledge = knowledgeModel.state.value.selected?.reviewed() == true,
        knowledgeCardOpen = navigation?.currentDestination?.route == "knowledge/{code}",
        knowledgeCodes = knowledgeModel.state.value.visible.map { it.code },
        selectedKnowledgeCode = knowledgeModel.state.value.selectedCode,
        resultKnowledgeCodes = resultKnowledgeCodes(),
        availableModels = fieldModel.detector.models.map { it.id },
        plots = plotsModel.state.value.plots.map { it.plot.name },
        plotTargets = plotsModel.state.value.plots.map { PlotTarget(it.plot.id, it.plot.name) },
        pendingPlotDeletion = plotsModel.state.value.pendingDeletion,
        plot = fieldModel.plotName,
        row = fieldModel.rowName,
        plant = fieldModel.plantName,
        plantHistoryOpen = navigation?.currentDestination?.route == "plant/{id}",
        inspectionOpen = navigation?.currentDestination?.route == "inspection/{id}",
        journalIds = (if (journalModel.state.value.historyPlantId != null) journalModel.state.value.history
            else journalModel.state.value.visible).map { it.id },
        journalCount = journalModel.state.value.entries.size,
        settings = settings.state.value?.let { mapOf("voice_enabled" to it.voiceEnabled,
            "spoken_follow_up" to it.spokenFollowUp, "free_speech" to it.freeSpeech,
            "achievements" to it.achievements) }.orEmpty(),
        openedInspectionId = journalModel.state.value.opened?.details?.inspection?.id?.takeIf {
            it == navigation?.currentBackStackEntry?.arguments?.getString("id")
        },
        editingInspectionId = journalModel.state.value.noteDraft?.id,
        resultOpen = navigation?.currentDestination?.route == "result",
        followUp = followUp.awaitingAnswer
    )

    private fun resultKnowledgeCodes(): List<String?> {
        val classes = if (navigation?.currentDestination?.route == "inspection/{id}") {
            val saved = journalModel.state.value.opened?.takeIf {
                it.details.inspection.id == navigation?.currentBackStackEntry?.arguments?.getString("id")
            }
            saved?.details?.photos?.firstOrNull()?.detections.orEmpty()
                .sortedByDescending { it.confidence }.map { it.classCode }
        } else {
            fieldModel.result?.let { result -> result.boxes.map { result.model.classes[it.classId] } }.orEmpty()
        }
        return classes.map(knowledgeModel::codeForClass)
    }

    private val actionPort = object : ActionPort {
        override fun state() = currentState()

        override fun perform(operation: Operation) {
            when (operation) {
                Operation.ResumeDraft -> fieldModel.resumeDraft()
                Operation.DiscardDraft -> fieldModel.discardDraft()
                is Operation.SetContinuousListening -> {
                    if (!operation.enabled) {
                        fieldModel.noteReviewVoice = false
                    }
                    setContinuous(operation.enabled)
                }
                is Operation.ShowHelp -> photoFlow.helpOpen = operation.visible
                Operation.Exit -> finish()
                Operation.CloseNote -> closeNote()
                Operation.ClearNoteDraft -> {
                    editNote("")
                    speak("Текст заметки очищен. Скажи «добавь заметку», чтобы продиктовать новый текст, или «отмена».")
                }
                Operation.CloseJournal -> navigation?.popBackStack()
                Operation.CloseResult -> navigation?.popBackStack("main", false)
                Operation.CloseInspection -> {
                    journalModel.closeInspection()
                    navigation?.popBackStack()
                }
                // The dialog itself answers «Не сохраняю» after the verdict.
                Operation.EndFollowUp -> Unit
                Operation.ClosePhotoFlow -> closePhotoFlow()
                Operation.StopSpeech -> speaker.stop()
                is Operation.Scroll -> if (currentScreen() == Screen.JOURNAL) {
                    journalModel.scroll(operation.direction)
                } else {
                    photoFlow.scroll(operation.direction)
                }
                is Operation.OpenNote -> openNote(operation.dictate)
                Operation.OpenJournal -> {
                    journalModel.refresh()
                    navigation?.navigate("journal") { launchSingleTop = true }
                }
                is Operation.OpenInspection -> {
                    voice.stop()
                    speaker.stop()
                    journalModel.openInspection(operation.id)
                    navigation?.navigate("inspection/${Uri.encode(operation.id)}") {
                        popUpTo(if (journalModel.state.value.historyPlantId != null) "plant/{id}" else "journal")
                        launchSingleTop = true
                    }
                }
                is Operation.SpeakInspection -> {
                    val saved = journalModel.state.value.opened
                    if (saved?.details?.inspection?.id == operation.id) {
                        speak(com.agronomy.phytomonitor.journal.SavedInspectionSpeech.text(saved.details))
                    }
                }
                is Operation.OpenInspectionNote -> {
                    if (journalModel.beginNote(operation.id)) openNote(operation.dictate)
                }
                is Operation.SaveInspectionNote -> journalModel.saveNote(operation.id) { saved, previous ->
                    fieldModel.savedNoteChanged(saved.reportName, previous, saved.note)
                    closeNote()
                }
                Operation.OpenPlots -> navigation?.navigate("plots") { launchSingleTop = true }
                is Operation.CreatePlot -> plotsModel.createPlot(operation.name)
                is Operation.RenamePlot -> plotsModel.renamePlot(operation.id, operation.name)
                is Operation.AskDeletePlot -> {
                    plotsModel.askDelete(operation.target)
                    speak("Удалить участок «${operation.target.name}»? Осмотры останутся без участка. Скажи «подтвердить» или «отмена».")
                }
                is Operation.DeletePlot -> plotsModel.deletePlot(operation.target)
                Operation.CancelPlotDeletion -> plotsModel.cancelDelete()
                Operation.ClosePlots -> {
                    plotsModel.cancelDelete()
                    navigation?.popBackStack()
                }
                Operation.OpenSettings -> navigation?.navigate("settings") { launchSingleTop = true }
                is Operation.SetSetting -> lifecycleScope.launch {
                    when (operation.key) {
                        "voice_enabled" -> settingsModel.voiceEnabled(operation.enabled)
                        "spoken_follow_up" -> settingsModel.spokenFollowUp(operation.enabled)
                        "achievements" -> settingsModel.achievements(operation.enabled)
                        else -> settingsModel.freeSpeech(operation.enabled)
                    }.join()
                    val error = settingsModel.error.value
                    if (error != null) speak(error)
                    else speak(if (operation.key == "voice_enabled" && !operation.enabled)
                        "Голосовое управление выключено. Включить его снова можно переключателем в настройках."
                        else "Настройка сохранена.")
                }
                is Operation.Search -> {
                    if (operation.screen == Screen.JOURNAL) {
                        journalModel.query(operation.query)
                        if (currentState().inspectionOpen) {
                            journalModel.closeInspection()
                            navigation?.popBackStack("journal", false)
                        }
                    } else {
                        knowledgeModel.query(operation.query)
                        knowledgeModel.select(null)
                        navigation?.popBackStack("knowledge", false)
                    }
                }
                is Operation.FilterJournal -> journalModel.filter(operation.filter)
                is Operation.ExportJournal -> {
                    speak("Выбери место сохранения в системном окне Android. Голос AgroVoice этим окном не управляет.")
                    exportJournalFile(JournalExport.entries.first { it.name.equals(operation.format, ignoreCase = true) })
                }
                Operation.ImportJournal -> {
                    speak("Выбери архив или JSON в системном окне Android. Голос AgroVoice этим окном не управляет.")
                    importJournalFile()
                }
                Operation.CloseSettings -> navigation?.popBackStack()
                is Operation.OpenKnowledge -> {
                    voice.stop()
                    photoFlow.dialogVoice = operation.withVoice
                    knowledgeModel.open(fieldModel.crop)
                    navigation?.navigate("knowledge") { launchSingleTop = true }
                }
                is Operation.OpenKnowledgeCard -> {
                    voice.stop()
                    photoFlow.dialogVoice = operation.withVoice
                    knowledgeModel.select(operation.code)
                    navigation?.navigate("knowledge/${operation.code}") {
                        popUpTo("knowledge")
                        launchSingleTop = true
                    }
                    if (operation.withVoice) knowledgeModel.state.value.selected?.let {
                        speak("Карточка: ${it.name}. " + if (it.reviewed()) "Проверено специалистом." else "Черновик, требуется проверка.")
                    }
                }
                is Operation.OpenResultKnowledge -> {
                    voice.stop()
                    photoFlow.dialogVoice = operation.withVoice
                    knowledgeModel.open(operation.code.substringBefore("__"))
                    navigation?.navigate("knowledge") { launchSingleTop = true }
                    knowledgeModel.select(operation.code)
                    navigation?.navigate("knowledge/${operation.code}") { launchSingleTop = true }
                    if (operation.withVoice) speak("Справка по предварительному результату модели. " + knowledgeModel.spoken())
                }
                Operation.CloseKnowledge -> {
                    voice.stop()
                    photoFlow.dialogVoice = false
                    navigation?.popBackStack()
                }
                Operation.CloseKnowledgeCard -> {
                    knowledgeModel.select(null)
                    navigation?.popBackStack()
                }
                is Operation.FilterKnowledgeCrop -> {
                    voice.stop()
                    knowledgeModel.crop(operation.crop)
                    navigation?.popBackStack("knowledge", false)
                }
                Operation.ReadKnowledge -> speak(knowledgeModel.spoken())
                is Operation.SelectPlot -> fieldModel.editPlot(operation.name)
                is Operation.SetRow -> fieldModel.editRow(operation.row)
                is Operation.SetPlant -> fieldModel.editPlant(operation.name)
                is Operation.SetCameraGuidance -> cameraModel.guidance(operation.enabled)
                Operation.OpenPlantHistory -> {
                    val id = if (currentState().inspectionOpen) journalModel.state.value.opened?.details?.photos?.firstOrNull()?.photo?.plantId else null
                    if (currentState().inspectionOpen && id == null) speak("Этот осмотр не привязан к растению.")
                    else journalModel.findPlantHistory(id, fieldModel.plotName, fieldModel.plantName) { plantId ->
                        navigation?.navigate("plant/${Uri.encode(plantId)}") { launchSingleTop = true }
                    }
                }
                Operation.ClosePlantHistory -> {
                    journalModel.closePlantHistory()
                    navigation?.popBackStack()
                }
                is Operation.CompareInspections -> journalModel.compareInspections(operation.firstId, operation.secondId)
                Operation.SpeakPlantHistory -> speak(journalModel.state.value.comparison.joinToString("\n") {
                    com.agronomy.phytomonitor.journal.SavedInspectionSpeech.text(it.details)
                })
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
                    cameraModel.requestShot()
                }
                is Operation.SelectPhoto -> photoFlow.select(operation.number)
                Operation.ConfirmGallery -> confirmGallery()
                Operation.Analyze -> fieldModel.analyze()
            }
        }

        override fun tell(text: String, isError: Boolean, request: ActionRequest?) {
            if (cameraOpen) {
                cameraModel.error(text)
            } else if (galleryOpen) {
                photoFlow.error(text)
            } else if (isError) {
                fieldModel.showError(text)
            } else {
                fieldModel.notify(text)
            }
            // «Прочитай результат» without a result answers aloud, as the button did before.
            if (isError && (request?.action == VoiceAction.SPEAK_RESULT ||
                    (request?.source == ActionSource.VOICE && speaker.available))) {
                speak(text)
            }
            if (request?.source == ActionSource.VOICE) voice.feedback(text)
        }
    }
    internal val actions = ActionDispatcher(actionPort)
    private val followUp = FollowUpDialog()
    /** Compose-visible copy of the dialog phase: it decides whether to listen after speech. */
    private var followUpPhase by mutableStateOf(FollowUpDialog.Phase.IDLE)
    private val voiceRouter: VoiceRouter by lazy {
        VoiceRouter(::screenFromNavigation, { foreground && settings.state.value?.voiceEnabled == true },
            actions, followUp, ::sayFollowUp, phaseChanged = { followUpPhase = it },
            followUpEnabled = { settings.state.value?.spokenFollowUp == true })
    }
    /** V-01: one headset button for hands-free work; see HeadsetButtonPolicy. */
    internal fun headsetPress() {
        if (!foreground) {
            return
        }
        val listening = voice.active || photoFlow.dialogVoice || photoFlow.continuousVoice
        when (HeadsetButtonPolicy.press(currentScreen(), speaker.active, listening, followUp.awaitingAnswer)) {
            HeadsetPress.TAKE_SHOT, HeadsetPress.ANSWER_YES -> handleVoiceCommand(VoiceCommand(VoiceAction.CONFIRM, "Кнопка гарнитуры"))
            HeadsetPress.STOP_SPEECH -> speaker.stop()
            HeadsetPress.STOP_LISTENING, HeadsetPress.START_LISTENING -> voiceButton()
            HeadsetPress.NONE -> Unit
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in HeadsetButtonPolicy.keys) {
            if (event.repeatCount == 0) {
                headsetPress()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun voiceButton() {
        if (settings.state.value?.voiceEnabled != true) {
            fieldModel.showError("Включи голосовое управление в настройках приложения.")
            return
        }
        if(voice.active || photoFlow.dialogVoice || photoFlow.continuousVoice){setContinuous(false);return}
        requestContinuous=false
        if(!hasMicrophone())microphone.launch(Manifest.permission.RECORD_AUDIO) else startOneShot()
    }
    private fun hasMicrophone()=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun currentGrammar()=when {
        fieldModel.noteOpen->if(fieldModel.dictating)null else VoiceCommands.noteReviewGrammar
        // Free speech: the whole Russian vocabulary of the model, understood by FreeSpeech rules.
        photoFlow.freeSpeech->null
        cameraOpen->VoiceCommands.cameraGrammar
        galleryOpen->VoiceCommands.galleryGrammar(photoFlow.photos.size)
        currentScreen() in setOf(Screen.KNOWLEDGE, Screen.JOURNAL, Screen.PLOTS, Screen.SETTINGS) -> null // Search and new names need unrestricted vocabulary.
        else -> null // Plant names, like plot creation and search, need unrestricted vocabulary.
    }
    private fun currentScreen(): Screen = voiceRouter.screen
    private fun screenFromNavigation(): Screen = when {
        fieldModel.noteOpen -> if (fieldModel.dictating) Screen.NOTE else Screen.NOTE_REVIEW
        cameraOpen -> Screen.CAMERA
        galleryOpen -> Screen.GALLERY
        navigation?.currentDestination?.route in setOf("journal", "inspection/{id}", "plant/{id}") -> Screen.JOURNAL
        navigation?.currentDestination?.route == "settings" -> Screen.SETTINGS
        navigation?.currentDestination?.route in setOf("knowledge", "knowledge/{code}") -> Screen.KNOWLEDGE
        navigation?.currentDestination?.route == "plots" -> Screen.PLOTS
        else -> Screen.MAIN
    }
    private fun openNote(dictate: Boolean=false){voice.stop();speaker.stop();fieldModel.noteOpen=true;fieldModel.noteReviewVoice=false;fieldModel.dictating=dictate && hasMicrophone();if(dictate && !hasMicrophone()){requestDictation=true;microphone.launch(Manifest.permission.RECORD_AUDIO)}}
    private fun closeNote() {
        voice.stop()
        fieldModel.dictating = false
        fieldModel.noteReviewVoice = false
        fieldModel.noteOpen = false
        journalModel.cancelNote()
    }
    private fun editNote(text: String) {
        if (journalModel.state.value.noteDraft != null) journalModel.editNote(text) else fieldModel.editNote(text)
    }
    private fun dictateNote(){
        if (settings.state.value?.voiceEnabled != true) {
            fieldModel.showError("Включи голосовое управление в настройках приложения.")
            return
        }
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
        if (settings.state.value?.voiceEnabled != true) return
        if(galleryOpen){photoFlow.dialogVoice=true;return}
        voice.plotNames=plotsModel.state.value.plots.map {it.plot.name}
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
        if (enabled && settings.state.value?.voiceEnabled != true) {
            fieldModel.showError("Включи голосовое управление в настройках приложения.")
            return
        }
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
        headset=HeadsetButton(this,::headsetPress)
        speaker=OfflineResultSpeaker(applicationContext)
        photoFlow=ViewModelProvider(this)[PhotoFlowViewModel::class.java]
        cameraModel=ViewModelProvider(this)[CameraViewModel::class.java]
        fieldModel=ViewModelProvider(this)[FieldDemoViewModel::class.java]
        journalModel=ViewModelProvider(this)[JournalViewModel::class.java]
        journalModel.connect(fieldModel.journalReady)
        plotsModel=ViewModelProvider(this)[PlotsViewModel::class.java]
        plotsModel.connect(fieldModel.journalReady)
        settingsModel=ViewModelProvider(this)[SettingsViewModel::class.java]
        knowledgeModel=ViewModelProvider(this)[KnowledgeViewModel::class.java]
        voice.onCommand=::handleVoiceCommand
        voice.onCommands=voiceRouter::plan
        voice.onFailure={photoFlow.setContinuous(false);photoFlow.dialogVoice=false}
        voice.onDictation = { text ->
            val draft = journalModel.state.value.noteDraft?.text ?: fieldModel.noteDraft
            editNote((draft + " " + text).trim())
        }
        voice.onDictationFinished={fieldModel.dictating=false;fieldModel.noteReviewVoice=true}
        setContent {
            AgroVoiceAITheme {
                val model=fieldModel
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route ?: "main"
                var shownReport by rememberSaveable { mutableStateOf<String?>(null) }
                val journalState by journalModel.state.collectAsState()
                val plotsState by plotsModel.state.collectAsState()
                val cameraState by cameraModel.state.collectAsState()
                val galleryState by photoFlow.state.collectAsState()
                val currentSettings by settingsModel.state.collectAsState()
                SideEffect { navigation = navController }
                SideEffect { voice.plotNames = plotsState.plots.map { it.plot.name } }
                LaunchedEffect(model.busy, model.reportName, route) {
                    if (!model.busy && model.result != null && route == "main" && shownReport != model.reportName) {
                        shownReport = model.reportName
                        navController.navigate("result") { launchSingleTop = true }
                    } else if (!model.busy && model.result == null && route == "result") {
                        navController.popBackStack("main", false)
                    }
                }
                LaunchedEffect(model.locationEnabled, foreground) {
                    if (foreground && model.locationEnabled && model.location.hasPermission()) startLocation()
                }
                LaunchedEffect(currentSettings?.voiceEnabled, currentSettings?.spokenFollowUp) {
                    if (currentSettings?.voiceEnabled == false) {
                        setContinuous(false)
                        voice.stop()
                    }
                    if (currentSettings?.spokenFollowUp == false) {
                        followUp.stop()
                        followUpPhase = followUp.phase
                    }
                }
                LaunchedEffect(currentSettings?.achievements) {
                    journalModel.setAchievementsEnabled(currentSettings?.achievements != false)
                }
                val gallery=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null)model.importPhoto(uri) }
                // One document type per format, so the file picker suggests the right extension.
                val exports=JournalExport.entries.associateWith {format->
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)){uri->if(uri!=null)journalModel.export(uri,format)}
                }
                val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)journalModel.restore(uri)}
                val systemPicker: ()->Unit={closePhotoFlow();gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
                SideEffect {systemGallery=systemPicker}
                val context=currentScreen().protocolName
                val eligible=foreground && currentSettings?.voiceEnabled == true && !speaker.active && !galleryState.loading && !cameraState.shotRequested && !cameraState.capturing
                LaunchedEffect(context,model.dictating,model.noteReviewVoice,photoFlow.continuousVoice,photoFlow.dialogVoice,eligible,voice.active,followUpPhase) {
                    val answering=!voice.active && followUp.shouldListenAgain()
                    followUpPhase=followUp.phase
                    if(eligible && !voice.active && hasMicrophone() && (if(model.noteOpen)model.dictating || model.noteReviewVoice else photoFlow.continuousVoice || photoFlow.dialogVoice || answering)) {
                        delay(400)
                        voice.plotNames=plotsModel.state.value.plots.map {it.plot.name}
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
                LaunchedEffect(model.busy, journalState.busy, journalState.openingId, plotsState.busy) {
                    if (!currentState().busy) {
                        actions.settled(failed = (model.message != null && model.messageIsError) ||
                            (currentScreen() == Screen.JOURNAL && journalState.messageIsError) ||
                            (currentScreen() == Screen.PLOTS && plotsState.messageIsError))
                    }
                }
                LaunchedEffect(plotsState.message) {
                    if (foreground && currentScreen() == Screen.PLOTS) plotsState.message?.let(::speak)
                }
                LaunchedEffect(journalState.message) {
                    if (foreground && (currentScreen() in setOf(Screen.MAIN, Screen.JOURNAL) || journalState.noteDraft != null)) {
                        journalState.message?.let(::speak)
                    }
                }
                LaunchedEffect(model.busy) {
                    if(!model.busy){
                        val failed=model.message!=null && model.messageIsError
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
                SideEffect {
                    exportJournalFile = exportJournal
                    importJournalFile = importJournal
                }
                // Confirmations of finished actions («сохранено», «снято») appear for a moment; errors stay as banners.
                val snackbar=remember {SnackbarHostState()}
                val notice=model.message?.takeIf {!model.messageIsError || route != "main"}
                LaunchedEffect(notice) {
                    if(notice!=null){snackbar.showSnackbar(notice,withDismissAction=false,duration=SnackbarDuration.Short);model.clearMessage()}
                }
                Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snackbar)},
                    bottomBar = {
                        if (route !in setOf("main", "result", "camera", "gallery")) {
                            VoiceStrip(voice, photoFlow, ::voiceButton, ::setContinuous, hint = "Скажи действие или «стоп».")
                        }
                    }) { padding ->
                    Box(Modifier.padding(padding)) {
                        NavHost(navController, startDestination = "main",
                            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                                composable("main") {
                                    FieldDemoScreen(model,Modifier,onAction=::press,voice=voice,flow=photoFlow,onVoice=::voiceButton,onContinuous=::setContinuous,speaking=speaker.active,speakerStatus=speaker.status,onLocation=::toggleLocation,
                                        showResult = false, onOpenResult = { navController.navigate("result") { launchSingleTop = true } })
                                }
                                composable("result") {
                                    FieldDemoScreen(model,Modifier,onAction=::press,voice=voice,flow=photoFlow,onVoice=::voiceButton,onContinuous=::setContinuous,speaking=speaker.active,speakerStatus=speaker.status,onLocation=::toggleLocation,
                                        onBackToPhoto = { press(VoiceAction.BACK) })
                                }
                                composable("camera") {
                                    PlantCamera(cameraModel, onSaved = { uri ->
                                        if (foreground && cameraOpen) {
                                            closePhotoFlow()
                                            navController.popBackStack("main", false)
                                            model.importPhoto(uri)
                                        }
                                    }, onCancel = { press(VoiceAction.CANCEL) }, onShoot = { press(VoiceAction.CONFIRM) },
                                        onGuidance = { press(VoiceAction.CAMERA_GUIDANCE, it.toString()) },
                                        onAdvice = { advice ->
                                            if (foreground && cameraOpen && !speaker.active && !voice.active && settings.state.value?.voiceEnabled == true) {
                                                speak(advice)
                                                true
                                            } else false
                                        }) {
                                        VoiceStrip(voice,photoFlow,::voiceButton,::setContinuous,hint="Наведи камеру → «подтвердить».",onDark=true)
                                    }
                                }
                                composable("gallery") {
                                    LaunchedEffect(Unit) {
                                        if (photoFlow.photos.isEmpty() && !photoFlow.loading) photoFlow.openGallery(false)
                                    }
                                    NumberedGallery(photoFlow, { press(VoiceAction.CONFIRM) }, { press(VoiceAction.CANCEL) }, systemPicker,
                                        onSelect = { press(VoiceAction.PHOTO_NUMBER, it.toString()) }) {
                                        VoiceStrip(voice,photoFlow,::voiceButton,::setContinuous,hint="Номер → просмотр → «подтвердить».")
                                    }
                                }
                                composable("journal") {
                                    LaunchedEffect(Unit) { journalModel.closePlantHistory() }
                                    JournalScreen(journalModel, model.detector.models.associate { it.id to it.name },
                                        onOpen = { id -> press(VoiceAction.JOURNAL_NUMBER, (journalModel.state.value.visible.indexOfFirst { it.id == id } + 1).toString()) },
                                        onBack = { press(VoiceAction.BACK) },
                                        onExport = { press(VoiceAction.EXPORT_JOURNAL, it.name.lowercase()) },
                                        onImport = { press(VoiceAction.IMPORT_JOURNAL) },
                                        onQuery = { press(VoiceAction.SEARCH, it) }, onFilter = { press(VoiceAction.JOURNAL_FILTER, it) })
                                }
                                composable("settings") {
                                    SettingsScreen(settingsModel, onBack = { press(VoiceAction.BACK) },
                                        onSetting = { key, enabled -> press(VoiceAction.SET_SETTING, "$key:$enabled") },
                                        earned = journalState.earned)
                                }
                                composable("plots") {
                                    PlotsScreen(plotsModel, model.plotName, model.rowName,
                                        onBack = { press(VoiceAction.BACK) }, onSelect = { name -> press(VoiceAction.PLOT, name) },
                                        onAction = ::press)
                                }
                                composable("knowledge") {
                                    LaunchedEffect(Unit) { knowledgeModel.select(null) }
                                    KnowledgeScreen(knowledgeModel, onBack = { press(VoiceAction.BACK) },
                                        onOpen = { code -> press(VoiceAction.KNOWLEDGE_NUMBER, (knowledgeModel.state.value.visible.indexOfFirst { it.code == code } + 1).toString()) },
                                        onRead = { press(VoiceAction.READ_KNOWLEDGE) },
                                        onCrop = { press(VoiceAction.CROP, it) }, onQuery = { press(VoiceAction.SEARCH, it) })
                                }
                                composable("knowledge/{code}") { entry ->
                                    LaunchedEffect(entry) { knowledgeModel.select(entry.arguments?.getString("code")) }
                                    KnowledgeScreen(knowledgeModel, onBack = { press(VoiceAction.BACK) },
                                        onOpen = { code -> press(VoiceAction.KNOWLEDGE_NUMBER, (knowledgeModel.state.value.visible.indexOfFirst { it.code == code } + 1).toString()) },
                                        onRead = { press(VoiceAction.READ_KNOWLEDGE) },
                                        onCrop = { press(VoiceAction.CROP, it) }, onQuery = { press(VoiceAction.SEARCH, it) })
                                }
                                composable("inspection/{id}") { entry ->
                                    val id = requireNotNull(entry.arguments?.getString("id"))
                                    LaunchedEffect(id) { journalModel.openInspection(id) }
                                    val saved = journalState.opened?.takeIf { it.details.inspection.id == id }
                                    if (saved == null) {
                                        Column {
                                            Text(journalState.message ?: "Открываю сохранённый осмотр…")
                                            TextButton(onClick = { press(VoiceAction.BACK) }) { Text("Назад в журнал") }
                                        }
                                    } else {
                                        SavedInspectionScreen(saved, onBack = { press(VoiceAction.BACK) },
                                            onPlantHistory = { press(VoiceAction.PLANT_HISTORY) },
                                            onRead = { press(VoiceAction.SPEAK_RESULT) },
                                            onKnowledge = { press(VoiceAction.LEAF_KNOWLEDGE, it.toString()) },
                                            onEditNote = { press(VoiceAction.NOTE) }, onReanalyse = {
                                            val path = saved.details.photos.firstOrNull()?.photo?.localPath
                                            if (path != null) {
                                                journalModel.closeInspection()
                                                navController.popBackStack("main", false)
                                                model.openFile(File(path))
                                            }
                                        })
                                    }
                                }
                                composable("plant/{id}") { entry ->
                                    val id = requireNotNull(entry.arguments?.getString("id"))
                                    LaunchedEffect(id) { journalModel.openPlantHistory(id) }
                                    PlantHistoryScreen(journalState, onBack = { press(VoiceAction.BACK) },
                                        onOpen = { press(VoiceAction.JOURNAL_NUMBER, it.toString()) },
                                        onCompare = { press(VoiceAction.COMPARE_INSPECTIONS, it) },
                                        onRead = { press(VoiceAction.SPEAK_RESULT) })
                                }
                        }
                        InspectionDialogs(model, ::dictateNote, onConfirm = { press(VoiceAction.CONFIRM) },
                            onCloseNote = { press(VoiceAction.CANCEL) },
                            note = journalState.noteDraft?.text ?: model.noteDraft, onEditNote = ::editNote,
                            savedInspection = journalState.noteDraft != null, error = journalState.message,
                            saving = journalState.busy)
                        if (photoFlow.helpOpen) VoiceHelpDialog { press(VoiceAction.CLOSE_HELP) }
                    }
                }
                BackHandler(model.noteOpen || route != "main" || photoFlow.helpOpen) {
                    press(VoiceAction.BACK)
                }
            }
        }
    }
    override fun onResume(){super.onResume();foreground=true;headset.setActive(true);if(fieldModel.locationEnabled && fieldModel.location.hasPermission())startLocation()}
    override fun onStop() {
        foreground = false
        plotsModel.cancelDelete()
        headset.setActive(false)
        actions.cancelPlan()
        followUp.stop()
        followUpPhase = followUp.phase
        fieldModel.location.stop()
        voice.stop()
        speaker.stop()
        if (!isChangingConfigurations) {
            fieldModel.dictating = false
            fieldModel.noteReviewVoice = false
            photoFlow.dialogVoice = false
            if (cameraOpen) closePhotoFlow()
        }
        super.onStop()
    }
    override fun onDestroy(){voice.close();speaker.close();headset.release();super.onDestroy()}
}

