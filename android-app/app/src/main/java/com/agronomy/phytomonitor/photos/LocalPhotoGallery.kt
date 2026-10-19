package com.agronomy.phytomonitor.photos

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executors

data class GalleryPhoto(val id: Long, val uri: Uri, val name: String, val takenMs: Long, val addedMs: Long) {
    val chronologicalMs: Long get()=takenMs.takeIf {it>0} ?: addedMs
}

object LocalPhotoGallery {
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT>=34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT>=33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun hasAccess(context: Context)=permissions().any {ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED}
    fun fullAccess(context: Context)=ContextCompat.checkSelfPermission(context,if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    fun load(context: Context): List<GalleryPhoto> {
        val collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.DATE_TAKEN,MediaStore.Images.Media.DATE_ADDED)
        val rows=mutableListOf<GalleryPhoto>()
        val selection=if(Build.VERSION.SDK_INT>=29)"${MediaStore.Images.Media.IS_PENDING}=0 AND ${MediaStore.Images.Media.SIZE}>0" else "${MediaStore.Images.Media.SIZE}>0"
        context.contentResolver.query(collection,projection,selection,null,null)?.use {cursor ->
            while(cursor.moveToNext()) {
                val id=cursor.getLong(0)
                rows+=GalleryPhoto(id,ContentUris.withAppendedId(collection,id),cursor.getString(1) ?: "Фото",cursor.getLong(2),cursor.getLong(3)*1000)
            }
        }
        return rows.sortedWith(compareByDescending<GalleryPhoto> {it.chronologicalMs}.thenByDescending {it.addedMs}.thenByDescending {it.id})
    }
    fun preview(context: Context, uri: Uri, maxSize: Int): Bitmap {
        if(Build.VERSION.SDK_INT>=29)return context.contentResolver.loadThumbnail(uri,Size(maxSize,maxSize),null)
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0){"Фото недоступно"}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)>maxSize*sample)sample*=2
        val options=BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        val image=context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,options)} ?: error("Фото недоступно")
        val orientation=runCatching {context.contentResolver.openInputStream(uri)!!.use {ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix=Matrix()
        when(orientation){
            ExifInterface.ORIENTATION_ROTATE_90->matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180->matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270->matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
            ExifInterface.ORIENTATION_TRANSVERSE->{matrix.setRotate(270f);matrix.postScale(-1f,1f)}
        }
        return if(matrix.isIdentity)image else Bitmap.createBitmap(image,0,0,image.width,image.height,matrix,true).also {if(it!==image)image.recycle()}
    }
}

class PhotoFlowViewModel(application: Application): AndroidViewModel(application) {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var generation=0
    @Volatile private var cleared=false
    var galleryOpen by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var photos by mutableStateOf<List<GalleryPhoto>>(emptyList()); private set
    var selectedNumber by mutableStateOf<Int?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var previewLoading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var partialAccess by mutableStateOf(false); private set
    var dialogVoice by mutableStateOf(false)
    private val voicePreferences=application.getSharedPreferences("voice_controls",0)
    var continuousVoice by mutableStateOf(voicePreferences.getBoolean("continuous",false)); private set
    var helpOpen by mutableStateOf(false)
    var scrollEvent by mutableStateOf(0);private set
    var scrollDirection="down";private set
    fun scroll(direction: String){scrollDirection=direction;scrollEvent++}
    fun setContinuous(value: Boolean){continuousVoice=value;voicePreferences.edit().putBoolean("continuous",value).apply()}
    /** Experimental free Russian speech: Vosk without a phrase list, FreeSpeech rules, the same executor. */
    var freeSpeech by mutableStateOf(voicePreferences.getBoolean("free_speech",false)); private set
    fun switchFreeSpeech(value: Boolean){freeSpeech=value;voicePreferences.edit().putBoolean("free_speech",value).apply()}
    var cameraOpen by mutableStateOf(false); private set
    var shotRequested by mutableStateOf(false); private set
    var cameraCapturing by mutableStateOf(false); private set
    val selected: GalleryPhoto? get()=selectedNumber?.let {photos.getOrNull(it-1)}
    fun openGallery(withVoice: Boolean) {
        generation++;val token=generation
        galleryOpen=true;cameraOpen=false;loading=true;photos=emptyList();selectedNumber=null;preview=null;message=null
        partialAccess=!LocalPhotoGallery.fullAccess(getApplication());dialogVoice=withVoice
        worker.execute {
            try {
                val items=LocalPhotoGallery.load(getApplication())
                main.post {if(!cleared && token==generation){photos=items;loading=false;if(items.isEmpty())message="Нет доступных фото. Проверь разрешение на фотографии."}}
            }catch(error:Exception){main.post {if(!cleared && token==generation){loading=false;message=error.message ?: "Не удалось открыть галерею";dialogVoice=false}}}
        }
    }
    fun select(number: Int) {
        if(!galleryOpen || loading)return
        if(number !in 1..photos.size){message="Нет фото №$number. Доступны номера от 1 до ${photos.size}.";return}
        selectedNumber=number;preview=null;previewLoading=true;message=null
        val token=++generation;val image=photos[number-1]
        worker.execute {
            try {
                val bitmap=LocalPhotoGallery.preview(getApplication(),image.uri,1080)
                main.post {if(!cleared && token==generation){preview=bitmap;previewLoading=false}else bitmap.recycle()}
            }catch(error:Exception){main.post {if(!cleared && token==generation){previewLoading=false;message="Не удалось открыть фото №$number. Выбери другое фото."}}}
        }
    }
    fun confirmation(): Uri? {
        if(selected==null){message="Сначала назови номер фото.";return null}
        if(preview==null || previewLoading){message="Дождись просмотра выбранного фото.";return null}
        return selected!!.uri
    }
    fun openCamera(automatic: Boolean){close();cameraOpen=true;shotRequested=automatic}
    fun requestShot(){if(cameraOpen && !cameraCapturing)shotRequested=true}
    fun consumeAutoShot(): Boolean {val value=shotRequested;shotRequested=false;return value}
    fun capturing(value: Boolean){cameraCapturing=value}
    fun clearSelection(){generation++;selectedNumber=null;preview=null;previewLoading=false;message=null}
    fun error(text: String){message=text}
    fun close(){generation++;galleryOpen=false;cameraOpen=false;dialogVoice=false;shotRequested=false;cameraCapturing=false;loading=false;previewLoading=false;preview=null;selectedNumber=null;photos=emptyList();message=null}
    override fun onCleared(){cleared=true;generation++;worker.shutdown();super.onCleared()}
}
