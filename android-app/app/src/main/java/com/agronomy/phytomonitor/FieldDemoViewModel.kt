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
    private var unsavedReport: File?=null // touched only on the worker thread
    private val journal=InspectionJournal(application)
    /** Receiver readings while the screen is open; a fresh fix is attached to the next saved inspection. */
    val location=FieldLocation(application)
    var locationEnabled by mutableStateOf(false); private set
    var rowName by mutableStateOf(""); private set
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
        get() = reportName != null && savedInspection == SavedInspection(reportName, noteDraft, review, plotName, rowName)

    /** Journal screens await the one startup migration before reading or restoring records. */
    val journalReady = CompletableDeferred<JournalReport?>()
    private val work = SerialModelWork(
        onFailure = ::postFailure,
        onClosed = { replaceUnsavedReport(null); detector.close() }
    )

    init {
        work.submit {migrateJournal()}
        viewModelScope.launch {
            val saved = settings.state.filterNotNull().first()
            selectedModel = saved.model.takeIf { id -> detector.models.any { it.id == id } } ?: detector.models.first().id
            crop = saved.crop.takeIf { it == "all" || it in currentModel().crops } ?: "all"
            threshold = saved.threshold
            plotName = saved.plot
            rowName = saved.row
            locationEnabled = saved.location
            pendingCapture = saved.pendingCamera
            busy = false
            val last = saved.lastPhoto?.let(::File)
            if (last?.isFile == true) openFile(last, analyzePhoto = false)
        }
        viewModelScope.launch {
            settings.error.filterNotNull().collect { showError(it) }
        }
        viewModelScope.launch {
            settings.state.filterNotNull().map { it.plot }.distinctUntilChanged().collect { plotName = it }
        }
    }

    fun setModel(id: String, analyzePhoto: Boolean=true) {
        if(busy || id==selectedModel || detector.models.none {it.id==id})return
        selectedModel=id; result=null; reportName=null;review="unrated";comparison=emptyList()
        settings.set(Keys.model, id)
        // A crop the new model does not know would hide every class.
        if(crop!="all" && crop !in currentModel().crops){crop="all";settings.set(Keys.crop, crop)}
        if(bitmap!=null && analyzePhoto) analyze()
    }
    fun selectCrop(value: String, analyzePhoto: Boolean=true) {
        if(busy || value==crop)return
        crop=value; result=null;reportName=null;review="unrated";comparison=emptyList()
        settings.set(Keys.crop, value)
        if(bitmap!=null && analyzePhoto) analyze()
    }
    /** Keeps the current result on screen while the slider moves; analyze() replaces it when the drag ends. */
    fun changeThreshold(value: Float) {
        if(busy)return
        threshold=value
        comparison=emptyList()
        settings.set(Keys.threshold, value)
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
    fun cropChoices(): List<Pair<String,String>> = listOf("all" to "Все культуры") + currentModel().let {m -> m.crops.map {it to (m.cropNames[it] ?: it)}}
    fun cropName(id: String): String = if(id=="all") "Все культуры" else currentModel().cropNames[id] ?: CropVocabulary.names[id] ?: id
    /** The model shown in the top bar: «YOLO26n · 25 %». */
    fun selectedModelName(): String = detector.models.firstOrNull {it.id==selectedModel}?.name ?: selectedModel
    fun importPhoto(uri: Uri) {
        if(busy)return
        photoQuality = null
        busy=true;message=null;result=null;reportName=null
        work.submit {
            try {
                val imported=PhotoFiles.import(getApplication(),uri)
                val decoded=PhotoFiles.decode(imported)
                publish { if(!cleared){replacePhoto(imported,decoded);busy=false;analyze()} else decoded.recycle() }
            } catch(error:Exception){postFailure(error)}
        }
    }
    fun openFile(file: File, analyzePhoto: Boolean=true) {
        if(busy)return
        photoQuality = null
        busy=true;message=null;result=null;reportName=null
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
        noteDraft="";review="unrated";comparison=emptyList()
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
                replaceUnsavedReport(report)
                publish {if(!cleared){result=detection;reportName=report.name;busy=false}}
            }catch(error:Exception){postFailure(error)}
        }
    }
    /**
     * Only a report saved to the journal is kept. Each new analysis deletes the previous unsaved report,
     * so results/ no longer grows with every re-check, model switch or app start. Runs on the worker thread.
     */
    private fun replaceUnsavedReport(report: File?) {
        unsavedReport?.delete()
        unsavedReport=report
    }
    fun editNote(text: String){noteDraft=text.take(4000)}
    fun editPlot(text: String){plotName=text.take(120);settings.set(Keys.plot, plotName)}
    /** The row of the next inspection: digits only, as in «ряд четырнадцать». */
    fun editRow(text: String){rowName=text.filter {it.isDigit()}.take(3);settings.set(Keys.row, rowName)}
    fun switchLocation(enabled: Boolean){locationEnabled=enabled;settings.set(Keys.location, enabled);if(!enabled)location.stop()}
    fun assess(value: String){if(value in setOf("unrated","correct","incorrect","uncertain")){review=value;saveInspection()}}
    fun saveInspection(){
        val name=reportName ?: run {showError("Сначала дождись результата анализа");return}
        if(busy)return
        val report=File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir,"results/$name")
        val note=noteDraft;val rating=review;val plot=plotName;val row=rowName
        val place=if(locationEnabled)LocationFixes.forInspection(location.latest,System.currentTimeMillis()) else null
        val saved=SavedInspection(name,note,rating,plot,row)
        busy=true
        message=null
        work.submit {try {
            journal.save(report,note,rating,plot,row,place)
            if(unsavedReport?.name==report.name)unsavedReport=null
            publish {if(!cleared){savedInspection=saved;busy=false;notify("Осмотр сохранён в журнале")}}
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
    private suspend fun publish(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
    private suspend fun postFailure(error: Exception) {
        if (error is CancellationException) throw error
        publish {
            if (!cleared) {
                busy = false
                showError(error.message ?: "Не удалось обработать фото")
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

private data class SavedInspection(val reportName: String?, val note: String, val review: String, val plot: String, val row: String)

enum class JournalExport(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    ARCHIVE("application/zip", "zip")
}
