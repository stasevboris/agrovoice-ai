package com.agronomy.phytomonitor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.agronomy.phytomonitor.ml.CropVocabulary
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.DetectorModel
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.journal.JournalIssue
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.draft.InspectionDraft
import com.agronomy.phytomonitor.draft.InspectionDraftStore
import com.agronomy.phytomonitor.draft.StaleDraftWriterException
import com.agronomy.phytomonitor.location.FieldLocation
import com.agronomy.phytomonitor.location.LocationFixes
import com.agronomy.phytomonitor.ml.PhotoFiles
import java.io.File
import com.agronomy.phytomonitor.ml.SerialModelWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.viewModelScope
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class FieldDemoViewModel(application: Application) : AndroidViewModel(application) {
    val detector=FieldDetector(application)
    private val settings = SettingsStore.get(application)
    private var pendingCapture: String? = null
    private var cleared=false
    private val journal=InspectionJournal(application)
    private val draftStore = InspectionDraftStore(File(application.filesDir, "inspection-draft.json")).writer()
    private var activeDraft: InspectionDraft? = null
    private var draftRevision = 0L
    private var pendingDraft by mutableStateOf<InspectionDraft?>(null)
    var damagedDraft by mutableStateOf(false); private set
    val hasDraft: Boolean get() = pendingDraft != null || damagedDraft
    val resultCrop: String get() = activeDraft?.reportJson?.let { org.json.JSONObject(it).optString("crop_filter", "all") } ?: "all"
    val resultThreshold: Float? get() = activeDraft?.reportJson?.let { org.json.JSONObject(it).optDouble("confidence_threshold").toFloat() }
    var draftSaving by mutableStateOf(false); private set
    var draftError by mutableStateOf<String?>(null); private set
    /** Receiver readings while the screen is open; a fresh fix is attached to the next saved inspection. */
    val location=FieldLocation(application)
    var locationEnabled by mutableStateOf(false); private set
    var rowName by mutableStateOf(""); private set
    var plantName by mutableStateOf(""); private set
    var noteDraft by mutableStateOf(""); private set
    var review by mutableStateOf("unrated"); private set
    var plotName by mutableStateOf(""); private set
    var comparison by mutableStateOf<List<Pair<String,DetectionResult>>>(emptyList()); private set
    var showBoxes by mutableStateOf(true)
    var noteOpen by mutableStateOf(false)
    var dictating by mutableStateOf(false)
    var noteReviewVoice by mutableStateOf(false)
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var photo by mutableStateOf<File?>(null); private set
    var result by mutableStateOf<DetectionResult?>(null); private set
    var photoQuality by mutableStateOf<PhotoQuality?>(null)
        private set
    var busy by mutableStateOf(true); private set
    var message by mutableStateOf<String?>(null); private set
    /** Errors stop the task; confirmations of a finished action do not. */
    var messageIsError by mutableStateOf(true); private set
    var reportName by mutableStateOf<String?>(null); private set
    var selectedModel by mutableStateOf("yolo26n"); private set
    /** Crop ids are taxonomy ids («potato»); values saved by older versions («Potato») are lower-cased. */
    var crop by mutableStateOf("all"); private set
    var threshold by mutableStateOf(0.25f); private set
    /** What the journal holds for the current result; a repeated «сохрани осмотр» compares against it. */
    private var savedInspection by mutableStateOf<SavedInspection?>(null)
    val inspectionSaved: Boolean
        get() = reportName != null && savedInspection == SavedInspection(reportName, noteDraft, review, plotName, rowName, plantName)

    /** Journal screens await the one startup migration before reading or restoring records. */
    val journalReady = CompletableDeferred<JournalReport?>()
    private val work = SerialModelWork(
        onFailure = ::postFailure,
        onClosed = { detector.close() }
    )

    init {
        work.submit {migrateJournal()}
        viewModelScope.launch {
            val saved = settings.state.filterNotNull().first()
            selectedModel = saved.model.takeIf { id -> detector.models.any { it.id == id } } ?: detector.models.first().id
            crop = saved.crop.takeIf { it == "all" || (currentModel().family != "detr" && it in currentModel().crops) } ?: "all"
            threshold = saved.threshold
            plotName = saved.plot
            rowName = saved.row
            locationEnabled = saved.location
            pendingCapture = saved.pendingCamera
            work.submit {
                try {
                    val draft = draftStore.read()
                    val existing = draft?.reportName?.let { JournalDatabase.get(application).journal().inspectionByReport(it) }
                    val committed = draft?.saveCompleted(existing?.updatedAt) == true
                    if (committed) draftStore.clear(requireNotNull(draft).id)
                    publish {
                        if (!cleared) {
                            pendingDraft = if (committed) null else draft
                            busy = false
                            if (committed) notify("Осмотр уже сохранён в журнале. Незавершённое подтверждение восстановлено.")
                        }
                    }
                } catch (error: Exception) {
                    publish {
                        busy = false
                        damagedDraft = true
                        draftError = "Не удалось прочитать черновик. Можно повторить чтение или начать новый осмотр: повреждённый файл будет сохранён отдельно, исходные фото и журнал останутся."
                        showError(requireNotNull(draftError))
                    }
                }
            }
        }
        viewModelScope.launch {
            settings.error.filterNotNull().collect { showError(it) }
        }
        viewModelScope.launch {
            settings.state.filterNotNull().map { it.plot }.distinctUntilChanged().collect {
                if (activeDraft == null && pendingDraft == null) plotName = it
            }
        }
    }

    fun setModel(id: String, analyzePhoto: Boolean=true) {
        if(busy || id==selectedModel || detector.models.none {it.id==id})return
        selectedModel=id; result=null; reportName=null;review="unrated";comparison=emptyList()
        settings.set(Keys.model, id)
        // A crop the new model does not know would hide every class.
        if(crop!="all" && (crop !in currentModel().crops || currentModel().family=="detr")){crop="all";settings.set(Keys.crop, crop)}
        persistDraft()
        if(bitmap!=null && analyzePhoto) analyze()
    }
    fun selectCrop(value: String, analyzePhoto: Boolean=true) {
        if(busy || value==crop)return
        if (value != "all" && currentModel().family == "detr") {
            showError("Выбор культуры недоступен для этой демонстрационной модели.")
            return
        }
        crop=value; result=null;reportName=null;review="unrated";comparison=emptyList()
        settings.set(Keys.crop, value)
        persistDraft()
        if(bitmap!=null && analyzePhoto) analyze()
    }
    /** Keeps the current result on screen while the slider moves; analyze() replaces it when the drag ends. */
    fun changeThreshold(value: Float) {
        if(busy)return
        threshold=value
        comparison=emptyList()
        settings.set(Keys.threshold, value)
        persistDraft()
    }

    fun createCapture(): File = PhotoFiles.newCapture(getApplication()).also {
        pendingCapture = it.absolutePath
        settings.set(Keys.pendingCamera, it.absolutePath)
    }
    fun captureComplete(success: Boolean) {
        val path=pendingCapture
        pendingCapture = null
        settings.set(Keys.pendingCamera, null)
        val file=path?.let(::File)
        if(success && file?.isFile==true && file.length()>0) openFile(file)
        else {file?.delete();notify("Съёмка отменена")}
    }
    fun showError(text: String){message=text;messageIsError=true}
    /** A finished action («сохранено»): shown briefly at the bottom, not as a banner in the page. */
    fun notify(text: String){message=text;messageIsError=false}
    fun clearMessage(){message=null;messageIsError=true}
    fun currentModel(): DetectorModel = detector.models.firstOrNull {it.id==selectedModel} ?: detector.models.first()
    /** «Все культуры» first, then the crops of the selected model with their Russian names. */
    fun cropChoices(): List<Pair<String,String>> = listOf("all" to "Все культуры") + currentModel().let {m ->
        if (m.family == "detr") emptyList() else m.crops.map {it to (m.cropNames[it] ?: it)}
    }
    fun cropName(id: String): String = if(id=="all") "Все культуры" else currentModel().cropNames[id] ?: CropVocabulary.names[id] ?: id
    /** The model shown in the top bar: «YOLO26n · 25 %». */
    fun selectedModelName(): String = detector.models.firstOrNull {it.id==selectedModel}?.name ?: selectedModel
    fun importPhoto(uri: Uri) {
        if(busy || hasDraft || draftError != null)return
        busy=true;message=null
        work.submit {
            try {
                val imported=PhotoFiles.import(getApplication(),uri)
                val decoded=PhotoFiles.decode(imported)
                publish { if(!cleared){replacePhoto(imported,decoded);busy=false;analyze()} else decoded.recycle() }
            } catch(error:Exception){postFailure(error)}
        }
    }
    fun openFile(file: File, analyzePhoto: Boolean=true) {
        if(busy || hasDraft || draftError != null)return
        busy=true;message=null
        work.submit {
            try {
                val decoded=PhotoFiles.decode(file)
                publish {if(!cleared){replacePhoto(file,decoded);busy=false;if(analyzePhoto)analyze()}else decoded.recycle()}
            }catch(error:Exception){postFailure(error)}
        }
    }
    private fun replacePhoto(file: File,decoded: Bitmap){
        // Old images are left to GC after Compose has stopped drawing them.
        bitmap=decoded;photo=file
        result = null
        reportName = null
        photoQuality = null
        noteDraft="";review="unrated";comparison=emptyList()
        savedInspection = null
        activeDraft = InspectionDraft(photo = file.absolutePath)
        persistDraft()
        settings.set(Keys.lastPhoto, file.absolutePath)
    }
    fun analyze(){
        val source=bitmap ?: return
        val file=photo ?: return
        if(busy)return
        val model=detector.models.firstOrNull {it.id==selectedModel} ?: return
        val cropSnapshot=crop;val thresholdSnapshot=threshold
        busy=true;message=null;result=null;reportName=null
        work.submit {
            try {
                val quality = PhotoFiles.measureQuality(source)
                publish { if (!cleared) photoQuality = quality }
                val detection = detector.detect(source, model, cropSnapshot, thresholdSnapshot)
                    .copy(photoQuality = quality)
                val report=PhotoFiles.saveResult(getApplication(),file,source,detection,detector,cropSnapshot,thresholdSnapshot)
                val reportJson = report.readText()
                val snapshot = withContext(Dispatchers.Main.immediate) {
                    result = detection
                    reportName = report.name
                    activeDraft = requireNotNull(snapshotDraft()).copy(
                        reportJson = reportJson, catalogJson = detector.manifest.toString())
                    requireNotNull(activeDraft)
                }
                writeDraft(snapshot)
                publish {if(!cleared)busy=false}
            }catch(error:Exception){postFailure(error)}
        }
    }
    fun editNote(text: String){noteDraft=text.take(4000);persistDraft()}
    fun savedNoteChanged(name: String, before: String, after: String) {
        if (reportName != name) return
        if (noteDraft == before) noteDraft = after
        savedInspection = savedInspection?.copy(note = after)
        persistDraft()
    }
    fun editPlot(text: String) {
        val next = text.take(120)
        if (next != plotName) plantName = ""
        plotName = next
        settings.set(Keys.plot, plotName)
        persistDraft()
    }
    fun editPlant(text: String) { plantName = text.take(120); persistDraft() }
    /** The row of the next inspection: digits only, as in «ряд четырнадцать». */
    fun editRow(text: String){rowName=text.filter {it.isDigit()}.take(3);settings.set(Keys.row, rowName);persistDraft()}
    fun switchLocation(enabled: Boolean){locationEnabled=enabled;settings.set(Keys.location, enabled);if(!enabled)location.stop()}
    fun assess(value: String){if(value in setOf("unrated","correct","incorrect","uncertain")){review=value;saveInspection()}}
    fun saveInspection(){
        val name=reportName ?: run {showError("Сначала дождись результата анализа");return}
        if(busy)return
        val report=File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir,"results/$name")
        val note=noteDraft;val rating=review;val plot=plotName;val row=rowName
        val place=if(locationEnabled)LocationFixes.forInspection(location.latest,System.currentTimeMillis()) else null
        val plant = plantName
        val achievementsEnabled = settings.state.value?.achievements != false
        val saved=SavedInspection(name,note,rating,plot,row,plant)
        val snapshot = snapshotDraft() ?: return
        busy=true
        message=null
        work.submit {try {
            journal.achievementsEnabled = achievementsEnabled
            val previous = JournalDatabase.get(getApplication()).journal().inspectionByReport(name)
            val saving = snapshot.copy(saveRequested = true, savedBeforeVersion = previous?.updatedAt)
            writeDraft(saving)
            draftStore.withOwnership {
                journal.save(report,note,rating,plot,row,place,plant)
                draftStore.clear(snapshot.id)
            }
            publish {if(!cleared){savedInspection=saved;busy=false;draftError=null;notify("Осмотр сохранён в журнале")}}
        }catch(error: Exception){postFailure(error)}}
    }
    /** Runs on the worker before any journal read or write. Old JSON files are kept. */
    private fun migrateJournal() {
        val report = try {
            journal.migrateLegacy()
        } catch (error: Exception) {
            JournalReport(InspectionJournal.SOURCE_LEGACY, issues = listOf(JournalIssue("журнал", error.message ?: "перенос не выполнен")))
        }
        journalReady.complete(report)
    }
    fun compareModels(){
        val source=bitmap ?: run {showError("Сначала выбери фото");return};val file=photo ?: return
        if(busy)return
        val filter=crop;val score=threshold
        busy=true;message=null;comparison=emptyList()
        work.submit {try {
            // Comparison results are shown on screen only; they are not journal records.
            val rows=detector.models.map {m -> m.name to detector.detect(source,m,filter,score)}
            publish {if(!cleared){comparison=rows;busy=false;notify("Модели проверены на одном фото при одинаковом пороге")}}
        }catch(error: Exception){postFailure(error)}}
    }
    fun spokenResult(): String {
        val found=result ?: return "Сначала сделай фото и дождись анализа."
        return spokenAnalysis(found.boxes.map { found.model.labels[it.classId] }, found.photoQuality)
    }
    private fun snapshotDraft(): InspectionDraft? {
        val current = activeDraft ?: return null
        return current.copy(photo = photo?.absolutePath ?: current.photo, reportName = reportName,
            reportJson = current.reportJson.takeIf { current.reportName == reportName },
            catalogJson = current.catalogJson.takeIf { current.reportName == reportName },
            note = noteDraft, review = review, plot = plotName, row = rowName, plant = plantName,
            model = selectedModel, crop = crop, threshold = threshold, saveRequested = false,
            savedBeforeVersion = null)
    }

    private fun persistDraft() {
        val snapshot = snapshotDraft() ?: return
        activeDraft = snapshot
        draftSaving = true
        val revision = ++draftRevision
        work.submit {
            // Read the latest state when the serial worker reaches the request: edits made during
            // inference must not overwrite the analysis completed ahead of them with an old result.
            val latest = withContext(Dispatchers.Main.immediate) {
                snapshotDraft()?.takeIf { it.id == snapshot.id && revision == draftRevision && !inspectionSaved }
            }
            if (latest != null) {
                // A background autosave failure must not mark an inference queued after it as idle.
                try { writeDraft(latest) } catch (_: Exception) { /* writeDraft reports durable state. */ }
            }
            else publish { if (revision == draftRevision) draftSaving = false }
        }
    }

    private suspend fun writeDraft(snapshot: InspectionDraft) {
        val revision = withContext(Dispatchers.Main.immediate) { draftRevision }
        try {
            draftStore.write(snapshot)
            publish {
                if (draftRevision == revision) draftSaving = false
                draftError = null
            }
        } catch (error: Exception) {
            publish {
                draftSaving = false
                draftError = if (error is StaleDraftWriterException) {
                    "Черновик уже открыт в другом окне. Продолжи осмотр в нём: это окно не меняет сохранённые данные."
                } else {
                    "Не удалось записать изменения черновика. Фото и ранее записанная версия, если она была, сохранены; последние правки пока только на экране. Освободи место и повтори сохранение."
                }
                if (!cleared) showError(requireNotNull(draftError))
            }
            throw error
        }
    }

    fun resumeDraft() {
        if (damagedDraft) {
            retryDraftRead()
            return
        }
        val draft = pendingDraft ?: return
        if (busy) return
        busy = true
        work.submit {
            try {
                val file = File(draft.photo)
                val restoredResult = draft.result()
                val decoded = PhotoFiles.decode(file)
                publish {
                    if (cleared) decoded.recycle()
                    else {
                        bitmap = decoded
                        photo = file
                        result = restoredResult
                        photoQuality = restoredResult?.photoQuality
                        reportName = draft.reportName
                        plotName = draft.plot
                        rowName = draft.row
                        plantName = draft.plant
                        noteDraft = draft.note
                        review = draft.review
                        selectedModel = draft.model.takeIf { id -> detector.models.any { it.id == id } } ?: selectedModel
                        crop = draft.crop
                        threshold = draft.threshold
                        activeDraft = draft
                        pendingDraft = null
                        busy = false
                        notify("Черновик восстановлен. Фото, заметка и исходный результат сохранены.")
                    }
                }
            } catch (error: Exception) {
                publish {
                    busy = false
                    showError("Черновик не открыт: ${error.message}. Его файл сохранён; проверь доступность исходного фото и повтори.")
                }
            }
        }
    }

    fun discardDraft() {
        val draft = pendingDraft ?: activeDraft
        if (draft == null && !damagedDraft) return
        if (busy) return
        busy = true
        work.submit {
            try {
                val recovery = if (damagedDraft) draftStore.preserveDamaged() else {
                    draftStore.clear(requireNotNull(draft).id)
                    null
                }
                publish {
                    if (!cleared) {
                        pendingDraft = null
                        activeDraft = null
                        photo = null
                        bitmap = null
                        result = null
                        reportName = null
                        noteDraft = ""
                        review = "unrated"
                        savedInspection = null
                        draftError = null
                        damagedDraft = false
                        busy = false
                        settings.set(Keys.lastPhoto, null)
                        notify(if (recovery != null) "Можно начать новый осмотр. Повреждённый файл сохранён отдельно; исходные фото и журнал сохранены."
                            else "Черновик отменён. Исходные фото и журнал сохранены.")
                    }
                }
            } catch (error: Exception) { postFailure(error) }
        }
    }
    private fun retryDraftRead() {
        if (busy) return
        busy = true
        work.submit {
            try {
                val draft = draftStore.read()
                publish {
                    if (!cleared) {
                        pendingDraft = draft
                        damagedDraft = false
                        draftError = null
                        message = null
                        busy = false
                    }
                }
            } catch (_: Exception) {
                publish {
                    if (!cleared) {
                        busy = false
                        showError("Черновик пока не читается. Начни новый осмотр: исходный файл будет сохранён отдельно.")
                    }
                }
            }
        }
    }
    private suspend fun publish(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
    private suspend fun postFailure(error: Exception) {
        if (error is CancellationException) throw error
        publish {
            if (!cleared) {
                busy = false
                showError(draftError ?: error.message ?: "Не удалось обработать фото")
            }
        }
    }
    override fun onCleared(){
        cleared=true
        location.stop()
        work.close()
        super.onCleared()
    }
}

private data class SavedInspection(val reportName: String?, val note: String, val review: String, val plot: String, val row: String, val plant: String)

enum class JournalExport(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    ARCHIVE("application/zip", "zip")
}
