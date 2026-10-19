package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction

/** Screen names match `screen` in ml-pipeline/assistant/scenarios.json. */
enum class Screen(val protocolName: String) {
    MAIN("main"),
    CAMERA("camera"),
    GALLERY("gallery"),
    JOURNAL("journal"),
    PLOTS("plots"),
    SETTINGS("settings"),
    KNOWLEDGE("knowledge"),
    NOTE("note"),
    NOTE_REVIEW("note_review");

    companion object {
        fun fromProtocol(name: String): Screen? = entries.firstOrNull { it.protocolName == name }
    }
}

/** A tap is explicit; a phrase is one fixed command; the assistant is a model proposal checked like the others. */
enum class ActionSource { BUTTON, VOICE, ASSISTANT }

/** One typed request: the action set is [VoiceAction], shared with the assistant corpus. */
data class ActionRequest(
    val action: VoiceAction,
    val value: String = "",
    val source: ActionSource
)

/** What the executor may rely on. Built from the view models right before every action. */
data class AppState(
    val screen: Screen = Screen.MAIN,
    val hasPhoto: Boolean = false,
    val hasResult: Boolean = false,
    val busy: Boolean = false,
    val inspectionSaved: Boolean = false,
    val review: String = "unrated",
    val galleryCount: Int = 0,
    val selectedPhoto: Int? = null,
    val cameraCapturing: Boolean = false,
    val speaking: Boolean = false,
    val helpOpen: Boolean = false,
    val threshold: Float = 0.25f,
    val selectedModel: String = "",
    val crop: String = "all",
    /** Crop ids the selected model can filter by; «all» is always allowed. */
    val crops: List<String> = emptyList(),
    val canRefineCrop: Boolean = true,
    val availableModels: List<String> = emptyList(),
    val verifiedKnowledge: Boolean = false,
    val knowledgeCardOpen: Boolean = false,
    val knowledgeCodes: List<String> = emptyList(),
    val selectedKnowledgeCode: String? = null,
    val plots: List<String> = emptyList(),
    val plotTargets: List<PlotTarget> = emptyList(),
    val pendingPlotDeletion: PlotTarget? = null,
    val plot: String = "",
    val row: String = "",
    val inspectionOpen: Boolean = false,
    val journalIds: List<String> = emptyList(),
    val journalCount: Int = 0,
    val settings: Map<String, Boolean> = emptyMap(),
    val openedInspectionId: String? = null,
    val editingInspectionId: String? = null,
    val resultOpen: Boolean = false,
    /** The app has just asked «Сохранить осмотр?» and waits for «да» or «нет». */
    val followUp: Boolean = false
) {
    /** The object «да / подтвердить» would refer to, as `pending` in the corpus. */
    val pending: String?
        get() = when {
            pendingPlotDeletion != null && screen == Screen.PLOTS -> "delete_plot"
            followUp && screen == Screen.MAIN -> "save"
            else -> when (screen) {
                Screen.NOTE_REVIEW -> "note"
                Screen.CAMERA -> "capture"
                else -> null
            }
        }
}

/** A concrete change of the app. The Activity performs it without further decisions. */
sealed interface Operation {
    data class SetContinuousListening(val enabled: Boolean) : Operation
    data class ShowHelp(val visible: Boolean) : Operation
    data object Exit : Operation
    data object CloseNote : Operation
    data object ClearNoteDraft : Operation
    data object CloseJournal : Operation
    data object CloseInspection : Operation
    data object CloseResult : Operation
    data object EndFollowUp : Operation
    data object ClosePhotoFlow : Operation
    data object StopSpeech : Operation
    data class Scroll(val direction: String) : Operation
    data class OpenNote(val dictate: Boolean) : Operation
    data object OpenJournal : Operation
    data class OpenInspection(val id: String) : Operation
    data class SpeakInspection(val id: String) : Operation
    data class OpenInspectionNote(val id: String, val dictate: Boolean) : Operation
    data class SaveInspectionNote(val id: String) : Operation
    data object OpenPlots : Operation
    data class CreatePlot(val name: String) : Operation
    data class RenamePlot(val id: String, val name: String) : Operation
    data class AskDeletePlot(val target: PlotTarget) : Operation
    data class DeletePlot(val target: PlotTarget) : Operation
    data object CancelPlotDeletion : Operation
    data object ClosePlots : Operation
    data object OpenSettings : Operation
    data class SetSetting(val key: String, val enabled: Boolean) : Operation
    data class Search(val screen: Screen, val query: String) : Operation
    data class FilterJournal(val filter: String) : Operation
    data class ExportJournal(val format: String) : Operation
    data object ImportJournal : Operation
    data object CloseSettings : Operation
    data class OpenKnowledge(val withVoice: Boolean) : Operation
    data class OpenKnowledgeCard(val code: String, val withVoice: Boolean) : Operation
    data object CloseKnowledge : Operation
    data object CloseKnowledgeCard : Operation
    data class FilterKnowledgeCrop(val crop: String) : Operation
    data object ReadKnowledge : Operation
    data class SelectPlot(val name: String) : Operation
    data class SetRow(val row: String) : Operation
    data object SaveInspection : Operation
    data class Assess(val review: String) : Operation
    data object CompareModels : Operation
    data object SpeakResult : Operation
    data class ShowBoxes(val visible: Boolean) : Operation
    data class SelectModel(val id: String, val analyze: Boolean) : Operation
    data class SelectCrop(val id: String, val analyze: Boolean) : Operation
    data class SetThreshold(val value: Float, val analyze: Boolean) : Operation
    data object SystemGallery : Operation
    data class OpenGallery(val withVoice: Boolean) : Operation
    data class OpenCamera(val immediate: Boolean, val withVoice: Boolean) : Operation
    data object TakeShot : Operation
    data class SelectPhoto(val number: Int) : Operation
    data object ConfirmGallery : Operation
    data object Analyze : Operation
}

/** The executor's answer to one request. Only [Run] changes the app. */
sealed interface Verdict {
    data class Run(
        val operations: List<Operation>,
        val notice: String? = null,
        val noticeIsError: Boolean = false
    ) : Verdict

    /** The request is understood but impossible right now: missing photo, result, screen or range. */
    data class Clarify(val text: String) : Verdict

    /** The request is outside the app: unknown model or crop, malformed arguments. */
    data class Reject(val text: String) : Verdict

    /** The goal is already reached, for example the inspection is saved. Nothing is repeated. */
    data class Skip(val text: String) : Verdict

    /** A note is being dictated: speech is note text, never a command. */
    data object Dictate : Verdict

    /** The same recognized result arrived again within the repeat window. */
    data object Repeated : Verdict
}
