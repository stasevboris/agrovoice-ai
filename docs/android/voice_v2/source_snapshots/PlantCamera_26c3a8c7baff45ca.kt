package com.agronomy.phytomonitor.photos

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlantCamera(flow: PhotoFlowViewModel,onSaved: (Uri)->Unit,onCancel: ()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val previewView=remember {PreviewView(context).apply {implementationMode=PreviewView.ImplementationMode.COMPATIBLE}}
    val capture=remember {ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()}
    val executor=remember {ContextCompat.getMainExecutor(context)}
    var ready by remember {mutableStateOf(false)}
    var shooting by remember {mutableStateOf(false)}
    var streamed by remember {mutableStateOf(false)}
    val saveCallback by rememberUpdatedState(onSaved)
    fun takePhoto() {
        if(!ready || shooting)return
        shooting=true
        capture.targetRotation=previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val now=System.currentTimeMillis()
        val name="AgroVoice_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date(now))+".jpg"
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN,now)
            if(Build.VERSION.SDK_INT>=29)put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/AgroVoice")
            else {
                val directory=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"AgroVoice").apply {mkdirs()}
                put(MediaStore.Images.Media.DATA,File(directory,name).absolutePath)
            }
        }
        val output=ImageCapture.OutputFileOptions.Builder(context.contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values).build()
        capture.takePicture(output,executor,object: ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                shooting=false
                result.savedUri?.let {saveCallback(it)} ?: flow.error("Камера не вернула сохранённый снимок")
            }
            override fun onError(error: ImageCaptureException){shooting=false;flow.error("Не удалось сделать фото: ${error.message}")}
        })
    }
    DisposableEffect(owner,previewView) {
        var provider: ProcessCameraProvider?=null
        var disposed=false
        val preview=Preview.Builder().build().also {it.surfaceProvider=previewView.surfaceProvider}
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({if(!disposed)runCatching {
            provider=future.get()
            provider!!.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,preview,capture)
            ready=true
        }.onFailure {flow.error("Камера недоступна: ${it.message}")}},executor)
        val observer=androidx.lifecycle.Observer<PreviewView.StreamState> {streamed=it==PreviewView.StreamState.STREAMING}
        previewView.previewStreamState.observe(owner,observer)
        onDispose {disposed=true;ready=false;previewView.previewStreamState.removeObserver(observer);provider?.unbind(preview,capture)}
    }
    LaunchedEffect(ready,streamed) {
        if(ready && streamed) {
            delay(400) // Let the first exposure and autofocus settle; no second shutter command required.
            if(ready && streamed && flow.consumeAutoShot())takePhoto()
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Камера AgroVoice",style=MaterialTheme.typography.headlineSmall)
        Text(if(shooting)"Снимаю…" else "Наведи телефон на лист")
        AndroidView(factory={previewView},modifier=Modifier.fillMaxWidth().weight(1f))
        flow.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={takePhoto()},enabled=ready && !shooting,modifier=Modifier.fillMaxWidth()){Text("Снять сейчас")}
        OutlinedButton(onClick=onCancel,enabled=!shooting,modifier=Modifier.fillMaxWidth()){Text("Отмена")}
    }
}
