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
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.settings.Keys
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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

data class GalleryState(
    val loading: Boolean = false,
    val photos: List<GalleryPhoto> = emptyList(),
    val selectedNumber: Int? = null,
    val preview: Bitmap? = null,
    val previewLoading: Boolean = false,
    val message: String? = null,
    val partialAccess: Boolean = false
) {
    val selected: GalleryPhoto? get() = selectedNumber?.let { photos.getOrNull(it - 1) }
}

/** Gallery loading/selection is cancelled when leaving the destination or choosing another photo. */
class PhotoFlowViewModel(application: Application): AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GalleryState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    val loading get() = state.value.loading
    val photos get() = state.value.photos
    val selectedNumber get() = state.value.selectedNumber
    val preview get() = state.value.preview
    val previewLoading get() = state.value.previewLoading
    val message get() = state.value.message
    val selected get() = state.value.selected
    var dialogVoice by mutableStateOf(false)
    private val settings = SettingsStore.get(application)
    var continuousVoice by mutableStateOf(false); private set
    var helpOpen by mutableStateOf(false)
    var scrollEvent by mutableStateOf(0);private set
    var scrollDirection="down";private set
    fun scroll(direction: String){scrollDirection=direction;scrollEvent++}
    fun setContinuous(value: Boolean){continuousVoice=value;settings.set(Keys.continuous, value)}
    /** Experimental free Russian speech: Vosk without a phrase list, FreeSpeech rules, the same executor. */
    var freeSpeech by mutableStateOf(false); private set
    fun switchFreeSpeech(value: Boolean){freeSpeech=value;settings.set(Keys.freeSpeech, value)}
    init {
        viewModelScope.launch {
            settings.state.filterNotNull().collect {
                continuousVoice = it.continuous
                freeSpeech = it.freeSpeech
            }
        }
    }
    fun openGallery(withVoice: Boolean) {
        request?.cancel()
        mutableState.value = GalleryState(loading = true, partialAccess = !LocalPhotoGallery.fullAccess(getApplication()))
        dialogVoice = withVoice
        request = viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { LocalPhotoGallery.load(getApplication()) }
                mutableState.update { it.copy(photos = items, loading = false,
                    message = if (items.isEmpty()) "Нет доступных фото. Проверь разрешение на фотографии." else null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, message = error.message ?: "Не удалось открыть галерею") }
                dialogVoice = false
            }
        }
    }
    fun select(number: Int) {
        if (loading) return
        if (number !in 1..photos.size) {
            error("Нет фото №$number. Доступны номера от 1 до ${photos.size}.")
            return
        }
        request?.cancel()
        val image = photos[number - 1]
        mutableState.update { it.copy(selectedNumber = number, preview = null, previewLoading = true, message = null) }
        request = viewModelScope.launch {
            var decoded: Bitmap? = null
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    LocalPhotoGallery.preview(getApplication(), image.uri, 1080).also { decoded = it }
                }
                mutableState.update { it.copy(preview = bitmap, previewLoading = false) }
                decoded = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(previewLoading = false, message = "Не удалось открыть фото №$number. Выбери другое фото.") }
            } finally {
                decoded?.recycle()
            }
        }
    }
    fun confirmation(): Uri? {
        if(selected==null){error("Сначала назови номер фото.");return null}
        if(preview==null || previewLoading){error("Дождись просмотра выбранного фото.");return null}
        return selected!!.uri
    }
    fun clearSelection() {
        request?.cancel()
        mutableState.update { it.copy(selectedNumber = null, preview = null, previewLoading = false, message = null) }
    }
    fun error(text: String) = mutableState.update { it.copy(message = text) }
    fun close() {
        request?.cancel()
        dialogVoice = false
        mutableState.value = GalleryState()
    }
}
