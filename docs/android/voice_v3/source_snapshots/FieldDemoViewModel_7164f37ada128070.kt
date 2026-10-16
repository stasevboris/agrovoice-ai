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
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.ml.PhotoFiles
import java.io.File
import java.util.concurrent.Executors

class FieldDemoViewModel(application: Application) : AndroidViewModel(application) {
    val detector=FieldDetector(application)
    private val preferences=application.getSharedPreferences("field_demo",0)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var cleared=false
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var photo by mutableStateOf<File?>(null); private set
    var result by mutableStateOf<DetectionResult?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var reportName by mutableStateOf<String?>(null); private set
    var selectedModel by mutableStateOf(preferences.getString("model","yolo26n") ?: "yolo26n"); private set
    var crop by mutableStateOf(preferences.getString("crop","all") ?: "all"); private set
    var threshold by mutableStateOf(preferences.getFloat("threshold",0.25f).coerceIn(0.1f,0.8f)); private set

    init {
        if(detector.models.none {it.id==selectedModel})selectedModel=detector.models.first().id
        val last=preferences.getString("last_photo",null)?.let(::File)
        if(last?.isFile==true) openFile(last)
    }

    fun setModel(id: String, analyzePhoto: Boolean=true) {
        if(busy || id==selectedModel || detector.models.none {it.id==id})return
        selectedModel=id; result=null; reportName=null
        preferences.edit().putString("model",id).apply()
        if(bitmap!=null && analyzePhoto) analyze()
    }
    fun selectCrop(value: String, analyzePhoto: Boolean=true) {
        if(busy || value==crop)return
        crop=value; result=null;reportName=null
        preferences.edit().putString("crop",value).apply()
        if(bitmap!=null && analyzePhoto) analyze()
    }
    fun changeThreshold(value: Float) {
        if(busy)return
        threshold=value; result=null;reportName=null
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
        else {file?.delete();message="Съёмка отменена"}
    }
    fun showError(text: String){message=text}
    fun importPhoto(uri: Uri) {
        if(busy)return
        busy=true;message=null;result=null;reportName=null
        worker.execute {
            try {
                val imported=PhotoFiles.import(getApplication(),uri)
                val decoded=PhotoFiles.decode(imported)
                main.post { if(!cleared){replacePhoto(imported,decoded);busy=false;analyze()} }
            } catch(error:Exception){postFailure(error)}
        }
    }
    fun openFile(file: File) {
        if(busy)return
        busy=true;message=null;result=null;reportName=null
        worker.execute {
            try {
                val decoded=PhotoFiles.decode(file)
                main.post {if(!cleared){replacePhoto(file,decoded);busy=false;analyze()}else decoded.recycle()}
            }catch(error:Exception){postFailure(error)}
        }
    }
    private fun replacePhoto(file: File,decoded: Bitmap){
        // Old images are left to GC after Compose has stopped drawing them.
        bitmap=decoded;photo=file
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
                val detection=detector.detect(source,model,cropSnapshot,thresholdSnapshot)
                val report=PhotoFiles.saveResult(getApplication(),file,source,detection,detector,cropSnapshot,thresholdSnapshot)
                main.post {if(!cleared){result=detection;reportName=report.name;busy=false}}
            }catch(error:Exception){postFailure(error)}
        }
    }
    private fun postFailure(error:Exception){main.post {if(!cleared){busy=false;message=error.message ?: "Не удалось обработать фото"}}}
    override fun onCleared(){
        cleared=true
        worker.execute {detector.close()}
        worker.shutdown()
        super.onCleared()
    }
}
