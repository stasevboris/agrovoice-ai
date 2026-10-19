package com.agronomy.phytomonitor.photos

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.ui.MessageBanner
import com.agronomy.phytomonitor.ui.theme.DetectionAmber
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
fun PlantCamera(flow: CameraViewModel,onSaved: (Uri)->Unit,onCancel: ()->Unit,onShoot: ()->Unit,voicePanel: @Composable ()->Unit) {
    val state by flow.state.collectAsState()
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
        shooting=true;flow.capturing(true)
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
                shooting=false;flow.capturing(false)
                result.savedUri?.let {saveCallback(it)} ?: flow.error("Камера не вернула сохранённый снимок")
            }
            override fun onError(error: ImageCaptureException){shooting=false;flow.capturing(false);flow.error("Не удалось сделать фото: ${error.message}")}
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
    LaunchedEffect(ready,streamed,state.shotRequested) {
        if(ready && streamed) {
            delay(400) // Let the first exposure and autofocus settle; no second shutter command required.
            if(ready && streamed && flow.consumeAutoShot())takePhoto()
        }
    }
    CameraLayout(previewView, shooting, ready, state.message, onShoot=onShoot, onCancel=onCancel, voicePanel=voicePanel)
}

/**
 * Camera screen: the viewfinder owns the screen, the controls sit on a thin dark strip at the bottom.
 * The voice state is a single line above the shutter, so the preview is never covered by a card.
 */
@Composable
private fun CameraLayout(
    previewView: PreviewView,
    shooting: Boolean,
    ready: Boolean,
    message: String?,
    onShoot: () -> Unit,
    onCancel: () -> Unit,
    voicePanel: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        FramingGuide()
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, enabled = !shooting) {
                Icon(AgroIcons.Close, contentDescription = "Отмена", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Камера AgroVoice", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    if (shooting) "Снимаю…" else "Наведи телефон на лист и скажи «подтвердить»",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            message?.let { MessageBanner(it, isError = true) }
            voicePanel()
            ShutterButton(enabled = ready && !shooting, onClick = onShoot)
        }
    }
}

/** Corner marks in the middle of the preview: where the leaf should be. */
@Composable
private fun FramingGuide() {
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.7f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2 - size.height * 0.08f
        val arm = side * 0.14f
        val stroke = 4.dp.toPx()
        val color = DetectionAmber
        val corners = listOf(
            Offset(left, top) to Offset(1f, 1f),
            Offset(left + side, top) to Offset(-1f, 1f),
            Offset(left, top + side) to Offset(1f, -1f),
            Offset(left + side, top + side) to Offset(-1f, -1f)
        )
        for ((corner, direction) in corners) {
            drawLine(color, corner, Offset(corner.x + arm * direction.x, corner.y), stroke, StrokeCap.Round)
            drawLine(color, corner, Offset(corner.x, corner.y + arm * direction.y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, DetectionAmber, CircleShape)
            .semantics { contentDescription = "Снять сейчас" }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(AgroIcons.Camera, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
        }
    }
}
