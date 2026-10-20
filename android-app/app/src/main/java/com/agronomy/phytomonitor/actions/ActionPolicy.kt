package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import kotlin.math.roundToInt

/**
 * Decides what a request means in the current state. Pure Kotlin: the same rules serve buttons,
 * fixed voice phrases and assistant proposals, and are tested without a phone.
 */
object ActionPolicy {
    val reviews = setOf("correct", "incorrect", "uncertain")
    val scrollDirections = setOf("down", "up", "top", "bottom")
    const val MIN_THRESHOLD = 10
    const val MAX_THRESHOLD = 80
    const val THRESHOLD_STEP = 5
    const val MAX_ROW = 999

    const val WAIT = "Подожди: идёт обработка фото."
    const val NEED_PHOTO = "Сначала сделай фото или выбери снимок."
    const val NEED_RESULT = "Результата ещё нет. Сначала нужен анализ."
    const val NOTHING_TO_SAVE = "Нет готового осмотра для сохранения. Сначала нужен анализ."
    const val ALREADY_SAVED = "Осмотр уже сохранён в журнале."
    const val WHICH_ACTION = "Уточни, какое действие нужно выполнить."

    private val controlActions = setOf(
        VoiceAction.LISTEN_ON, VoiceAction.LISTEN_OFF, VoiceAction.HELP, VoiceAction.CLOSE_HELP,
        VoiceAction.EXIT, VoiceAction.BACK, VoiceAction.CANCEL, VoiceAction.SILENCE, VoiceAction.SCROLL
    )
    private val inspectionActions = setOf(
        VoiceAction.NOTE, VoiceAction.JOURNAL, VoiceAction.SAVE_INSPECTION, VoiceAction.ASSESS,
        VoiceAction.COMPARE, VoiceAction.SPEAK_RESULT, VoiceAction.SHOW_BOXES, VoiceAction.HIDE_BOXES,
        VoiceAction.ANALYZE
    )
    /** Reading the journal and hiding boxes do not touch the photo, so they work during analysis too. */
    private val availableWhileBusy = setOf(
        VoiceAction.JOURNAL, VoiceAction.SHOW_BOXES, VoiceAction.HIDE_BOXES,
        VoiceAction.PLOTS, VoiceAction.PLOT, VoiceAction.ROW, VoiceAction.SETTINGS, VoiceAction.KNOWLEDGE
    )
    /** The plot and the row describe the next inspection; they can be set next to the photo or in the plot list. */
    private val placeActions = setOf(VoiceAction.ROW, VoiceAction.PLOT)
    private val galleryActions = setOf(
        VoiceAction.PHOTO_NUMBER, VoiceAction.FIRST_PHOTO, VoiceAction.LAST_PHOTO,
        VoiceAction.NEXT_PHOTO, VoiceAction.PREVIOUS_PHOTO
    )
    private val actionsWithValue = setOf(
        VoiceAction.MODEL, VoiceAction.CROP, VoiceAction.THRESHOLD, VoiceAction.SCROLL,
        VoiceAction.PHOTO_NUMBER, VoiceAction.ASSESS, VoiceAction.ROW, VoiceAction.PLOT, VoiceAction.KNOWLEDGE_NUMBER,
        VoiceAction.JOURNAL_NUMBER, VoiceAction.CREATE_PLOT, VoiceAction.RENAME_PLOT, VoiceAction.DELETE_PLOT,
        VoiceAction.SET_SETTING, VoiceAction.SEARCH, VoiceAction.JOURNAL_FILTER, VoiceAction.EXPORT_JOURNAL, VoiceAction.LEAF_KNOWLEDGE,
        VoiceAction.PLANT, VoiceAction.COMPARE_INSPECTIONS, VoiceAction.CAMERA_GUIDANCE,
        VoiceAction.SELECT_FRAME, VoiceAction.FRAME_ROLE
    )

    fun resolve(request: ActionRequest, state: AppState): Verdict {
        argumentProblem(request, state)?.let { return it }
        if (state.screen == Screen.NOTE) {
            return resolveWhileDictating(request, state)
        }
        if (state.screen == Screen.NOTE_REVIEW) {
            return resolveNoteReview(request, state)
        }
        SyncActions.resolve(request, state)?.let { return it }
        if (request.action in controlActions) {
            return control(request, state)
        }
        if (state.pendingPlotDeletion != null) {
            if (state.busy) return Verdict.Clarify(WAIT)
            return if (request.action == VoiceAction.CONFIRM) PlotActions.confirm(state)
            else Verdict.Clarify("Сначала подтверди удаление участка или скажи «отмена».")
        }
        val readingKnowledge = state.screen == Screen.KNOWLEDGE && request.action in setOf(
            VoiceAction.CROP, VoiceAction.KNOWLEDGE_NUMBER, VoiceAction.KNOWLEDGE_NEXT,
            VoiceAction.KNOWLEDGE_PREVIOUS, VoiceAction.READ_KNOWLEDGE
        )
        if (state.busy && request.action !in availableWhileBusy && !readingKnowledge) {
            return Verdict.Clarify(WAIT)
        }
        return when (request.action) {
            in SeriesActions.actions -> SeriesActions.resolve(request, state)
            VoiceAction.RESUME_DRAFT, VoiceAction.DISCARD_DRAFT -> when {
                state.screen != Screen.MAIN -> Verdict.Clarify("Черновик доступен на главном экране.")
                !state.hasDraft -> Verdict.Skip("Нет черновика для восстановления.")
                request.action == VoiceAction.RESUME_DRAFT -> run(Operation.ResumeDraft)
                else -> run(Operation.DiscardDraft)
            }
            VoiceAction.CAMERA_GUIDANCE -> if (state.screen == Screen.CAMERA) run(Operation.SetCameraGuidance(request.value.toBoolean()))
                else Verdict.Clarify("Подсказки кадра доступны в камере.")
            VoiceAction.PLANT -> if (state.screen != Screen.MAIN) Verdict.Clarify("Назови растение на главном экране перед сохранением.")
                else run(Operation.SetPlant(request.value))
            VoiceAction.PLANT_HISTORY -> when {
                state.plantHistoryOpen -> Verdict.Skip("История растения уже открыта.")
                state.screen == Screen.MAIN || state.screen == Screen.JOURNAL && state.inspectionOpen -> run(Operation.OpenPlantHistory)
                else -> Verdict.Clarify("Выбери растение на главном экране или открой сохранённый осмотр.")
            }
            VoiceAction.COMPARE_INSPECTIONS -> {
                if (!state.plantHistoryOpen) Verdict.Clarify("Сначала открой историю растения.")
                else {
                    val indices = request.value.split(":").map { it.toInt() - 1 }
                    val ids = indices.mapNotNull(state.journalIds::getOrNull)
                    if (ids.size != 2 || ids[0] == ids[1]) Verdict.Clarify("Назови два разных номера из истории растения.")
                    else run(Operation.CompareInspections(ids[0], ids[1]))
                }
            }
            in BrowseActions.actions -> BrowseActions.resolve(request, state)
            in PlotActions.actions -> PlotActions.resolve(request, state)
            VoiceAction.JOURNAL_NUMBER -> openInspection(request, state)
            VoiceAction.NOTE -> if (state.screen == Screen.JOURNAL) {
                val id = state.openedInspectionId
                if (!state.inspectionOpen || id == null) Verdict.Clarify("Сначала открой осмотр для исправления заметки.")
                else run(Operation.OpenInspectionNote(id, request.source != ActionSource.BUTTON))
            } else inspection(request, state)
            VoiceAction.SPEAK_RESULT -> if (state.screen == Screen.JOURNAL) {
                val id = state.openedInspectionId
                when {
                    state.plantHistoryOpen -> run(Operation.SpeakPlantHistory)
                    !state.inspectionOpen || id == null -> Verdict.Clarify("Сначала открой сохранённый осмотр по номеру из журнала.")
                    state.speaking -> Verdict.Skip("Уже читаю результат.")
                    else -> run(Operation.SpeakInspection(id))
                }
            } else inspection(request, state)
            VoiceAction.KNOWLEDGE -> when (state.screen) {
                Screen.MAIN, Screen.JOURNAL -> run(Operation.OpenKnowledge(request.source != ActionSource.BUTTON))
                Screen.KNOWLEDGE -> Verdict.Skip("Справочник уже открыт.")
                else -> Verdict.Clarify(wrongScreenText(state.screen))
            }
            VoiceAction.READ_KNOWLEDGE -> if (state.screen == Screen.KNOWLEDGE) run(Operation.ReadKnowledge)
                else ResultKnowledge.resolve(request, state)
            VoiceAction.LEAF_KNOWLEDGE -> ResultKnowledge.resolve(request, state)
            VoiceAction.KNOWLEDGE_NUMBER, VoiceAction.KNOWLEDGE_NEXT, VoiceAction.KNOWLEDGE_PREVIOUS -> knowledgeCard(request, state)
            VoiceAction.CROP -> if (state.screen == Screen.KNOWLEDGE) run(Operation.FilterKnowledgeCrop(request.value))
                else capture(request, state)
            in placeActions -> place(request, state)
            VoiceAction.PLOTS -> openPlots(state)
            VoiceAction.SETTINGS -> when (state.screen) {
                Screen.MAIN -> run(Operation.OpenSettings)
                Screen.SETTINGS -> Verdict.Skip("Настройки уже открыты.")
                else -> Verdict.Clarify(wrongScreenText(state.screen))
            }
            in inspectionActions -> inspection(request, state)
            in galleryActions -> gallery(request, state)
            else -> capture(request, state)
        }
    }

    private fun openInspection(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.JOURNAL) return Verdict.Clarify("Сначала открой журнал.")
        if (state.journalIds.isEmpty()) return Verdict.Clarify("В текущем списке нет осмотров. Измени фильтр или сохрани осмотр.")
        val id = state.journalIds.getOrNull(request.value.toInt() - 1)
            ?: return Verdict.Clarify("Такого номера в текущем списке осмотров нет.")
        if (state.inspectionOpen && state.openedInspectionId == id) return Verdict.Skip("Этот осмотр уже открыт.")
        return run(Operation.OpenInspection(id))
    }

    private fun knowledgeCard(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.KNOWLEDGE) return Verdict.Clarify("Сначала открой справочник.")
        val current = state.knowledgeCodes.indexOf(state.selectedKnowledgeCode)
        val index = when (request.action) {
            VoiceAction.KNOWLEDGE_NEXT -> current + 1
            VoiceAction.KNOWLEDGE_PREVIOUS -> current - 1
            else -> (request.value.toIntOrNull() ?: 0) - 1
        }
        if (index !in state.knowledgeCodes.indices) return Verdict.Clarify("Такой карточки в текущем списке нет.")
        return run(Operation.OpenKnowledgeCard(state.knowledgeCodes[index], request.source != ActionSource.BUTTON))
    }

    private fun argumentProblem(request: ActionRequest, state: AppState): Verdict? {
        val value = request.value
        if (request.action !in actionsWithValue) {
            return if (value.isEmpty()) null else Verdict.Reject("У этого действия нет параметров.")
        }
        return when (request.action) {
            VoiceAction.SELECT_FRAME, VoiceAction.FRAME_ROLE -> null // SeriesActions validates range and role.
            VoiceAction.PLANT -> if (value.length <= 120 && value.none { it.isISOControl() }) null else Verdict.Reject("Название растения — до 120 символов.")
            VoiceAction.CAMERA_GUIDANCE -> if (value in setOf("true", "false")) null else Verdict.Reject("Укажи, включить или выключить подсказки.")
            VoiceAction.COMPARE_INSPECTIONS -> if (value.split(":").let { it.size == 2 && it.all { part -> (part.toIntOrNull() ?: 0) > 0 } }) null
                else Verdict.Clarify("Назови два номера: сравни осмотры один и два.")
            in BrowseActions.actions -> null
            in PlotActions.actions -> null // PlotActions checks the names and structured rename argument.
            VoiceAction.MODEL -> if (value in state.availableModels) null else Verdict.Reject("Модель недоступна в текущем приложении.")
            VoiceAction.CROP -> when {
                value != "all" && !state.canRefineCrop -> Verdict.Reject("Выбор культуры недоступен для этой демонстрационной модели.")
                value == "all" || value in state.crops -> null
                else -> Verdict.Reject("Эта культура недоступна для выбранной модели.")
            }
            VoiceAction.SCROLL -> if (value in scrollDirections) null else Verdict.Reject("Неизвестное направление прокрутки.")
            VoiceAction.ASSESS -> if (value in reviews) null else Verdict.Reject("Неизвестная оценка результата.")
            VoiceAction.THRESHOLD -> thresholdProblem(value)
            VoiceAction.ROW -> if ((value.toIntOrNull() ?: 0) in 1..MAX_ROW) null else Verdict.Clarify("Номер ряда — от 1 до $MAX_ROW.")
            VoiceAction.PLOT -> plotProblem(value, state)
            VoiceAction.KNOWLEDGE_NUMBER -> if ((value.toIntOrNull() ?: 0) >= 1) null else Verdict.Clarify("Назови номер карточки из списка.")
            VoiceAction.JOURNAL_NUMBER -> if ((value.toIntOrNull() ?: 0) >= 1) null else Verdict.Clarify("Назови номер осмотра из журнала.")
            VoiceAction.LEAF_KNOWLEDGE -> if ((value.toIntOrNull() ?: 0) >= 1) null else Verdict.Clarify("Назови номер листа из результата.")
            else -> if ((value.toIntOrNull() ?: 0) >= 1) null else Verdict.Clarify("Такого номера нет в галерее.")
        }
    }

    private fun plotProblem(name: String, state: AppState): Verdict? {
        if (state.plots.any { it.equals(name, ignoreCase = true) }) {
            return null
        }
        return Verdict.Clarify("Нет участка «$name». Создай его на экране «Участки».")
    }

    private fun place(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.MAIN && state.screen != Screen.PLOTS) {
            return Verdict.Clarify(wrongScreenText(state.screen))
        }
        if (request.action == VoiceAction.ROW) {
            return if (request.value == state.row) Verdict.Skip("Ряд ${request.value} уже указан.") else run(Operation.SetRow(request.value))
        }
        val name = state.plots.first { it.equals(request.value, ignoreCase = true) }
        return if (name == state.plot) Verdict.Skip("Участок «$name» уже выбран.") else run(Operation.SelectPlot(name))
    }

    private fun openPlots(state: AppState): Verdict = when (state.screen) {
        Screen.MAIN -> run(Operation.OpenPlots)
        Screen.PLOTS -> Verdict.Skip("Список участков уже открыт.")
        else -> Verdict.Clarify(wrongScreenText(state.screen))
    }

    private fun thresholdProblem(value: String): Verdict? {
        val percent = value.toIntOrNull() ?: return Verdict.Reject("Порог задаётся числом процентов.")
        if (percent in MIN_THRESHOLD..MAX_THRESHOLD) {
            return null
        }
        return Verdict.Clarify("Допустимый порог — от $MIN_THRESHOLD до $MAX_THRESHOLD процентов.")
    }

    /** Dictation: speech belongs to the note. Only the dialog's own buttons act. */
    private fun resolveWhileDictating(request: ActionRequest, state: AppState): Verdict {
        if (request.source != ActionSource.BUTTON) {
            return Verdict.Dictate
        }
        return when (request.action) {
            VoiceAction.CONFIRM -> confirmNote(state)
            VoiceAction.CANCEL, VoiceAction.BACK -> run(Operation.CloseNote)
            else -> Verdict.Clarify("Сначала закончи заметку.")
        }
    }

    private fun resolveNoteReview(request: ActionRequest, state: AppState): Verdict = when (request.action) {
        VoiceAction.CLEAR_NOTE -> if (state.busy) Verdict.Clarify(WAIT) else run(Operation.ClearNoteDraft)
        VoiceAction.CONFIRM -> confirmNote(state)
        VoiceAction.CANCEL, VoiceAction.BACK -> run(Operation.CloseNote)
        VoiceAction.NOTE -> state.editingInspectionId?.let {
            run(Operation.OpenInspectionNote(it, request.source != ActionSource.BUTTON))
        } ?: run(Operation.OpenNote(dictate = request.source != ActionSource.BUTTON))
        VoiceAction.LISTEN_OFF -> run(Operation.SetContinuousListening(false))
        VoiceAction.SILENCE -> run(Operation.StopSpeech)
        else -> Verdict.Clarify("Скажи «подтвердить», чтобы сохранить заметку, или «отмена».")
    }

    /** «Подтвердить» in a note closes it and saves the inspection with the same checks as «сохрани осмотр». */
    private fun confirmNote(state: AppState): Verdict {
        if (state.busy) {
            return Verdict.Clarify(WAIT)
        }
        state.editingInspectionId?.let { return run(Operation.SaveInspectionNote(it)) }
        val save = ActionRequest(VoiceAction.SAVE_INSPECTION, source = ActionSource.BUTTON)
        return when (val verdict = inspection(save, state.copy(screen = Screen.MAIN))) {
            is Verdict.Run -> Verdict.Run(listOf(Operation.CloseNote) + verdict.operations)
            is Verdict.Skip -> Verdict.Run(listOf(Operation.CloseNote), notice = verdict.text)
            is Verdict.Clarify -> Verdict.Run(listOf(Operation.CloseNote), notice = verdict.text, noticeIsError = true)
            else -> verdict
        }
    }

    private fun control(request: ActionRequest, state: AppState): Verdict = when (request.action) {
        VoiceAction.LISTEN_ON -> run(Operation.SetContinuousListening(true))
        VoiceAction.LISTEN_OFF -> run(Operation.SetContinuousListening(false))
        VoiceAction.HELP -> run(Operation.ShowHelp(true))
        VoiceAction.CLOSE_HELP -> run(Operation.ShowHelp(false))
        VoiceAction.EXIT -> Verdict.Run(listOf(Operation.SetContinuousListening(false), Operation.Exit))
        VoiceAction.SILENCE -> run(Operation.StopSpeech)
        VoiceAction.SCROLL -> run(Operation.Scroll(request.value))
        else -> closeTopLayer(state)
    }

    /** «Назад» and «отмена» close the topmost layer only, one step per phrase. */
    private fun closeTopLayer(state: AppState): Verdict = when {
        state.helpOpen -> run(Operation.ShowHelp(false))
        state.pendingPlotDeletion != null -> run(Operation.CancelPlotDeletion)
        state.screen == Screen.KNOWLEDGE && state.knowledgeCardOpen -> run(Operation.CloseKnowledgeCard)
        state.screen == Screen.KNOWLEDGE -> run(Operation.CloseKnowledge)
        state.followUp && state.screen == Screen.MAIN -> run(Operation.EndFollowUp)
        state.resultOpen && state.screen == Screen.MAIN -> run(Operation.CloseResult)
        state.screen == Screen.JOURNAL && state.inspectionOpen -> run(Operation.CloseInspection)
        state.plantHistoryOpen -> run(Operation.ClosePlantHistory)
        state.screen == Screen.JOURNAL -> run(Operation.CloseJournal)
        state.screen == Screen.PLOTS -> run(Operation.ClosePlots)
        state.screen == Screen.SETTINGS -> run(Operation.CloseSettings)
        state.screen == Screen.CAMERA || state.screen == Screen.GALLERY -> run(Operation.ClosePhotoFlow)
        else -> Verdict.Skip("Нечего отменять.")
    }

    private fun inspection(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.MAIN) {
            return Verdict.Clarify(wrongScreenText(state.screen))
        }
        return when (request.action) {
            VoiceAction.NOTE -> run(Operation.OpenNote(dictate = request.source != ActionSource.BUTTON))
            VoiceAction.JOURNAL -> run(Operation.OpenJournal)
            VoiceAction.SHOW_BOXES -> run(Operation.ShowBoxes(true))
            VoiceAction.HIDE_BOXES -> run(Operation.ShowBoxes(false))
            VoiceAction.ANALYZE -> if (state.hasPhoto) run(Operation.Analyze) else Verdict.Clarify(NEED_PHOTO)
            VoiceAction.COMPARE -> if (state.hasPhoto) run(Operation.CompareModels) else Verdict.Clarify(NEED_PHOTO)
            VoiceAction.SPEAK_RESULT -> speakResult(state)
            VoiceAction.SAVE_INSPECTION -> saveInspection(state)
            else -> assess(request.value, state)
        }
    }

    private fun speakResult(state: AppState): Verdict = when {
        !state.hasResult -> Verdict.Clarify(NEED_RESULT)
        state.speaking -> Verdict.Skip("Уже читаю результат.")
        else -> run(Operation.SpeakResult)
    }

    private fun saveInspection(state: AppState): Verdict = when {
        !state.hasResult -> Verdict.Clarify(NOTHING_TO_SAVE)
        state.inspectionSaved -> Verdict.Skip(ALREADY_SAVED)
        else -> run(Operation.SaveInspection)
    }

    private fun assess(review: String, state: AppState): Verdict = when {
        !state.hasResult -> Verdict.Clarify(NEED_RESULT)
        state.inspectionSaved && state.review == review -> Verdict.Skip("Эта оценка уже сохранена.")
        else -> run(Operation.Assess(review))
    }

    private fun gallery(request: ActionRequest, state: AppState): Verdict {
        if (state.screen != Screen.GALLERY) {
            return Verdict.Clarify(wrongScreenText(state.screen))
        }
        val count = state.galleryCount
        if (count == 0) {
            return Verdict.Clarify("Галерея ещё загружается или в ней нет фото.")
        }
        val target = when (request.action) {
            VoiceAction.FIRST_PHOTO -> 1
            VoiceAction.LAST_PHOTO -> count
            VoiceAction.NEXT_PHOTO -> (state.selectedPhoto ?: 0) + 1
            VoiceAction.PREVIOUS_PHOTO -> (state.selectedPhoto ?: 2) - 1
            else -> request.value.toInt()
        }
        if (target !in 1..count) {
            return Verdict.Clarify("Такого номера нет в галерее: доступны номера от 1 до $count.")
        }
        return run(Operation.SelectPhoto(target))
    }

    /** Camera, gallery, confirmation and analysis settings. */
    private fun capture(request: ActionRequest, state: AppState): Verdict {
        val withVoice = request.source != ActionSource.BUTTON
        val analyze = state.screen == Screen.MAIN && state.hasPhoto
        return when (request.action) {
            VoiceAction.CAMERA, VoiceAction.OPEN_CAMERA ->
                if (state.screen == Screen.CAMERA) Verdict.Skip("Камера уже открыта. Скажи «подтвердить», чтобы снять.")
                else run(Operation.OpenCamera(immediate = false, withVoice = withVoice))
            VoiceAction.CAMERA_NOW ->
                if (state.screen == Screen.CAMERA) takeShot(state)
                else run(Operation.OpenCamera(immediate = true, withVoice = false))
            VoiceAction.GALLERY ->
                if (state.screen == Screen.GALLERY) Verdict.Skip("Галерея уже открыта.")
                else run(Operation.OpenGallery(withVoice))
            VoiceAction.SYSTEM_GALLERY -> run(Operation.SystemGallery)
            VoiceAction.MODEL ->
                if (request.value == state.selectedModel) Verdict.Skip("Эта модель уже выбрана.")
                else run(Operation.SelectModel(request.value, analyze))
            VoiceAction.CROP ->
                if (request.value == state.crop) Verdict.Skip("Эта культура уже выбрана.")
                else run(Operation.SelectCrop(request.value, analyze))
            VoiceAction.THRESHOLD -> setThreshold(request.value.toInt(), state, analyze)
            VoiceAction.THRESHOLD_UP -> setThreshold(percentOf(state.threshold) + THRESHOLD_STEP, state, analyze)
            VoiceAction.THRESHOLD_DOWN -> setThreshold(percentOf(state.threshold) - THRESHOLD_STEP, state, analyze)
            VoiceAction.CONFIRM -> confirm(state)
            else -> Verdict.Reject("Действие не поддерживается приложением.")
        }
    }

    private fun takeShot(state: AppState): Verdict =
        if (state.cameraCapturing) Verdict.Skip("Снимок уже делается.") else run(Operation.TakeShot)

    private fun confirm(state: AppState): Verdict = when (state.screen) {
        Screen.CAMERA -> takeShot(state)
        Screen.GALLERY ->
            if (state.selectedPhoto == null) Verdict.Clarify("Сначала назови номер фото.")
            else run(Operation.ConfirmGallery)
        Screen.MAIN -> if (state.followUp) saveInspection(state) else Verdict.Clarify(WHICH_ACTION)
        else -> Verdict.Clarify(WHICH_ACTION)
    }

    private fun setThreshold(percent: Int, state: AppState, analyze: Boolean): Verdict {
        val bounded = percent.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        if (bounded == percentOf(state.threshold)) {
            return Verdict.Skip("Порог уже $bounded %.")
        }
        return run(Operation.SetThreshold(bounded / 100f, analyze))
    }

    private fun percentOf(threshold: Float): Int = (threshold * 100).roundToInt()

    private fun wrongScreenText(screen: Screen): String = when (screen) {
        Screen.CAMERA -> "Наведи камеру и скажи «подтвердить» или «отмена»."
        Screen.GALLERY -> "Назови номер фото или скажи «подтвердить» / «отмена»."
        Screen.JOURNAL -> "Сначала закрой журнал: скажи «назад»."
        Screen.PLOTS -> "Сначала закрой список участков: скажи «назад»."
        Screen.SETTINGS -> "Сначала закрой настройки: скажи «назад»."
        Screen.KNOWLEDGE -> "Сначала закрой справочник: скажи «назад»."
        else -> "Сначала открой галерею, чтобы выбрать фото по номеру."
    }

    private fun run(operation: Operation) = Verdict.Run(listOf(operation))
}
