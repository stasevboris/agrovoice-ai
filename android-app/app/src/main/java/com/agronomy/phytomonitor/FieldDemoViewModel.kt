package com.agronomy.phytomonitor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.agronomy.phytomonitor.ml.CropVocabulary
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.DetectorModel
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.journal.FieldPlots
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.journal.JournalIssue
import com.agronomy.phytomonitor.journal.PlotChange
import com.agronomy.phytomonitor.journal.PlotSummary
import com.agronomy.phytomonitor.location.FieldLocation
import com.agronomy.phytomonitor.location.LocationFixes
import com.agronomy.phytomonitor.ml.PhotoFiles
import java.io.File
import java.util.concurrent.Executors

class FieldDemoViewModel(application: Application) : AndroidViewModel(application) {
    val detector=FieldDetector(application)
    private val preferences=application.getSharedPreferences("field_demo",0)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var cleared=false
    private var unsavedReport: File?=null // touched only on the worker thread
    private val journal=InspectionJournal(application)
    private val fieldPlots=FieldPlots(JournalDatabase.get(application))
    /** Receiver readings while the screen is open; a fresh fix is attached to the next saved inspection. */
    val location=FieldLocation(application)
    var locationEnabled by mutableStateOf(preferences.getBoolean("location",false)); private set
    var plotsOpen by mutableStateOf(false)
    var plots by mutableStateOf<List<PlotSummary>>(emptyList()); private set
    var rowName by mutableStateOf(preferences.getString("row","") ?: ""); private set
    /** A journal entry shown as saved: its own photo, frames and labels, without a new analysis. */
    var openedInspection by mutableStateOf<SavedInspectionView?>(null); private set
    var noteDraft by mutableStateOf(""); private set
    var review by mutableStateOf("unrated"); private set
    var journalOpen by mutableStateOf(false)
    var journalEntries by mutableStateOf<List<InspectionEntry>>(emptyList()); private set
    var journalQuery by mutableStateOf("")
    var journalFilter by mutableStateOf("all")
    var plotName by mutableStateOf(preferences.getString("plot","") ?: ""); private set
    val visibleInspections: List<InspectionEntry> get()=journalEntries.filter {
        (journalFilter=="all" || it.review==journalFilter) &&
            (journalQuery.isBlank() || listOf(it.plot,it.note,it.model).any {text->text.contains(journalQuery.trim(),ignoreCase=true)})
    }
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
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    /** Errors stop the task; confirmations of a finished action do not. */
    var messageIsError by mutableStateOf(true); private set
    var reportName by mutableStateOf<String?>(null); private set
    var selectedModel by mutableStateOf(preferences.getString("model","yolo26n") ?: "yolo26n"); private set
    /** Crop ids are taxonomy ids («potato»); values saved by older versions («Potato») are lower-cased. */
    var crop by mutableStateOf((preferences.getString("crop","all") ?: "all").lowercase()); private set
    var threshold by mutableStateOf(preferences.getFloat("threshold",0.25f).coerceIn(0.1f,0.8f)); private set
    /** What the journal holds for the current result; a repeated «сохрани осмотр» compares against it. */
    private var savedInspection by mutableStateOf<SavedInspection?>(null)
    val inspectionSaved: Boolean
        get() = reportName != null && savedInspection == SavedInspection(reportName, noteDraft, review, plotName, rowName)

    /** The last migration or import with its problems; shown in the journal until dismissed. */
    var journalReport by mutableStateOf<JournalReport?>(null); private set

    init {
        if(detector.models.none {it.id==selectedModel})selectedModel=detector.models.first().id
        if(crop!="all" && crop !in currentModel().crops)crop="all"
        worker.execute {migrateJournal()}
        refreshPlots()
        val last=preferences.getString("last_photo",null)?.let(::File)
        // The last photo comes back without a new analysis: the user decides when to check it.
        if(last?.isFile==true) openFile(last,analyzePhoto=false)
    }

    fun setModel(id: String, analyzePhoto: Boolean=true) {
        if(busy || id==selectedModel || detector.models.none {it.id==id})return
        selectedModel=id; result=null; reportName=null;review="unrated";comparison=emptyList()
        preferences.edit().putString("model",id).apply()
        // A crop the new model does not know would hide every class.
        if(crop!="all" && crop !in currentModel().crops){crop="all";preferences.edit().putString("crop",crop).apply()}
        if(bitmap!=null && analyzePhoto) analyze()
    }
    fun selectCrop(value: String, analyzePhoto: Boolean=true) {
        if(busy || value==crop)return
        crop=value; result=null;reportName=null;review="unrated";comparison=emptyList()
        preferences.edit().putString("crop",value).apply()
        if(bitmap!=null && analyzePhoto) analyze()
    }
    /** Keeps the current result on screen while the slider moves; analyze() replaces it when the drag ends. */
    fun changeThreshold(value: Float) {
        if(busy)return
        threshold=value
        comparison=emptyList()
        preferences.edit().putFloat("threshold",value).apply()
    }

    fun createCapture(): File = PhotoFiles.newCapture(getApplication()).also {
        preferences.edit().putString("pending_camera",it.absolutePath).apply()
    }
    fun captureComplete(success: Boolean) {
        val path=preferences.getString("pending_camera",null)
        preferences.edit().remove("pending_camera").apply()
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
        worker.execute {
            try {
                val imported=PhotoFiles.import(getApplication(),uri)
                val decoded=PhotoFiles.decode(imported)
                main.post { if(!cleared){replacePhoto(imported,decoded);busy=false;analyze()} }
            } catch(error:Exception){postFailure(error)}
        }
    }
    fun openFile(file: File, analyzePhoto: Boolean=true) {
        if(busy)return
        photoQuality = null
        busy=true;message=null;result=null;reportName=null
        worker.execute {
            try {
                val decoded=PhotoFiles.decode(file)
                main.post {if(!cleared){replacePhoto(file,decoded);busy=false;if(analyzePhoto)analyze()}else decoded.recycle()}
            }catch(error:Exception){postFailure(error)}
        }
    }
    private fun replacePhoto(file: File,decoded: Bitmap){
        // Old images are left to GC after Compose has stopped drawing them.
        bitmap=decoded;photo=file
        noteDraft="";review="unrated";comparison=emptyList()
        preferences.edit().putString("last_photo",file.absolutePath).apply()
    }
    fun analyze(){
        val source=bitmap ?: return
        val file=photo ?: return
        if(busy)return
        val model=detector.models.firstOrNull {it.id==selectedModel} ?: return
        val cropSnapshot=crop;val thresholdSnapshot=threshold
        busy=true;message=null;result=null;reportName=null
        worker.execute {
            try {
                val quality = PhotoFiles.measureQuality(source)
                main.post { if (!cleared) photoQuality = quality }
                val detection = detector.detect(source, model, cropSnapshot, thresholdSnapshot)
                    .copy(photoQuality = quality)
                val report=PhotoFiles.saveResult(getApplication(),file,source,detection,detector,cropSnapshot,thresholdSnapshot)
                replaceUnsavedReport(report)
                main.post {if(!cleared){result=detection;reportName=report.name;busy=false}}
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
    fun editPlot(text: String){plotName=text.take(120);preferences.edit().putString("plot",plotName).apply()}
    /** The row of the next inspection: digits only, as in «ряд четырнадцать». */
    fun editRow(text: String){rowName=text.filter {it.isDigit()}.take(3);preferences.edit().putString("row",rowName).apply()}
    fun switchLocation(enabled: Boolean){locationEnabled=enabled;preferences.edit().putBoolean("location",enabled).apply();if(!enabled)location.stop()}
    fun openPlots(){plotsOpen=true;refreshPlots()}
    fun refreshPlots(){worker.execute {val rows=fieldPlots.list();main.post {if(!cleared)plots=rows}}}
    fun createPlot(name: String){changePlots {fieldPlots.create(name)}}
    fun renamePlot(id: String, name: String){
        val renamingCurrent=plots.firstOrNull {it.plot.id==id}?.plot?.name==plotName
        changePlots {fieldPlots.rename(id,name).also {if(renamingCurrent && it is PlotChange.Done)main.post {editPlot(it.plot.name)}}}
    }
    fun deletePlot(id: String){
        val removed=plots.firstOrNull {it.plot.id==id}?.plot?.name
        worker.execute {
            fieldPlots.delete(id)
            val rows=fieldPlots.list()
            main.post {if(!cleared){plots=rows;if(removed==plotName)editPlot("");notify("Участок удалён. Его осмотры остались в журнале без участка.")}}
        }
    }
    private fun changePlots(change: ()->PlotChange){
        worker.execute {
            val outcome=change()
            val rows=fieldPlots.list()
            main.post {if(!cleared){plots=rows;if(outcome is PlotChange.Rejected)showError(outcome.reason) else clearMessage()}}
        }
    }
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
        worker.execute {try {
            journal.save(report,note,rating,plot,row,place)
            val rows=fieldPlots.list()
            main.post {if(!cleared)plots=rows}
            if(unsavedReport?.name==report.name)unsavedReport=null
            val entries=journal.list()
            main.post {if(!cleared){journalEntries=entries;savedInspection=saved;busy=false;notify("Осмотр сохранён в журнале")}}
        }catch(error: Exception){postFailure(error)}}
    }
    fun openInspection(id: String){
        worker.execute {
            val details=journal.details(id) ?: return@execute
            val path=details.photos.firstOrNull()?.photo?.localPath
            val photo=path?.let(::File)?.takeIf {it.isFile}?.let {runCatching {PhotoFiles.decode(it)}.getOrNull()}
            main.post {if(!cleared)openedInspection=SavedInspectionView.of(details,photo)}
        }
    }
    fun closeInspection(){openedInspection=null}
    /** An explicit new analysis of a saved photo; the saved inspection itself stays as it was. */
    fun reanalyseOpened(){
        val path=openedInspection?.details?.photos?.firstOrNull()?.photo?.localPath ?: return
        openedInspection=null
        journalOpen=false
        openFile(File(path))
    }
    fun openJournal(){journalOpen=true;worker.execute {val entries=journal.list();main.post {if(!cleared)journalEntries=entries}}}
    /** Runs on the worker before any journal read or write. Old JSON files are kept. */
    private fun migrateJournal() {
        val report = try {
            journal.migrateLegacy()
        } catch (error: Exception) {
            JournalReport(InspectionJournal.SOURCE_LEGACY, issues = listOf(JournalIssue("журнал", error.message ?: "перенос не выполнен")))
        }
        if (report != null && report.worthShowing) {
            main.post { if (!cleared) journalReport = report }
        }
    }
    fun dismissJournalReport() {
        journalReport = null
    }
    fun exportJournal(uri: Uri, format: JournalExport = JournalExport.JSON) {
        if(busy)return
        busy=true
        worker.execute {try {
            val output=requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")) {"Не удалось открыть файл"}
            val done=output.use {stream ->
                when(format) {
                    JournalExport.JSON -> {
                        stream.write(journal.export().toByteArray(Charsets.UTF_8))
                        "Журнал сохранён в файл JSON. Фотографии в него не входят."
                    }
                    JournalExport.CSV -> {
                        stream.write(journal.exportCsv().toByteArray(Charsets.UTF_8))
                        "Таблица CSV сохранена. Фотографии в неё не входят."
                    }
                    JournalExport.ARCHIVE -> {
                        val photos=journal.exportArchive(stream)
                        "Архив журнала сохранён, фото в нём: $photos."
                    }
                }
            }
            main.post {if(!cleared){busy=false;notify(done)}}
        }catch(error: Exception){postFailure(error)}}
    }
    /** A journal JSON or archive chosen by the user; the report lists what was taken and what was not. */
    fun importJournal(uri: Uri) {
        if(busy)return
        busy=true
        message=null
        worker.execute {try {
            val input=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) {"Не удалось открыть файл"}
            val report=input.use {journal.import(it)}
            val entries=journal.list()
            main.post {if(!cleared){journalEntries=entries;journalReport=report;busy=false}}
        }catch(error: Exception){postFailure(error)}}
    }
    fun compareModels(){
        val source=bitmap ?: run {showError("Сначала выбери фото");return};val file=photo ?: return
        if(busy)return
        val filter=crop;val score=threshold
        busy=true;message=null;comparison=emptyList()
        worker.execute {try {
            // Comparison results are shown on screen only; they are not journal records.
            val rows=detector.models.map {m -> m.name to detector.detect(source,m,filter,score)}
            main.post {if(!cleared){comparison=rows;busy=false;notify("Модели проверены на одном фото при одинаковом пороге")}}
        }catch(error: Exception){postFailure(error)}}
    }
    fun spokenResult(): String {
        val found=result ?: return "Сначала сделай фото и дождись анализа."
        return spokenAnalysis(found.boxes.map { found.model.labels[it.classId] }, found.photoQuality)
    }
    private fun postFailure(error:Exception){main.post {if(!cleared){busy=false;showError(error.message ?: "Не удалось обработать фото")}}}
    override fun onCleared(){
        cleared=true
        location.stop()
        worker.execute {replaceUnsavedReport(null);detector.close()}
        worker.shutdown()
        super.onCleared()
    }
}

private data class SavedInspection(val reportName: String?, val note: String, val review: String, val plot: String, val row: String)

enum class JournalExport(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    ARCHIVE("application/zip", "zip")
}
