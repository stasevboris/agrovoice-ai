package com.agronomy.phytomonitor.photos

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NumberedGallery(flow: PhotoFlowViewModel,onConfirm: ()->Unit,onCancel: ()->Unit,onSystemPicker: ()->Unit,voicePanel: @Composable ()->Unit) {
    var number by remember {mutableStateOf("")}
    val grid=rememberLazyGridState()
    LaunchedEffect(flow.selectedNumber) {flow.selectedNumber?.let {grid.animateScrollToItem(it-1);number=it.toString()}}
    var handledScroll by remember {mutableIntStateOf(flow.scrollEvent)}
    LaunchedEffect(flow.scrollEvent) {
        if(handledScroll!=flow.scrollEvent && flow.photos.isNotEmpty()){handledScroll=flow.scrollEvent
            when(flow.scrollDirection){"top"->grid.animateScrollToItem(0);"bottom"->grid.animateScrollToItem(flow.photos.lastIndex);else->grid.scrollBy(if(flow.scrollDirection=="down")500f else -500f)}
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Галерея · ${flow.photos.size} фото",style=MaterialTheme.typography.titleLarge)
        Text("№1 — самое новое, №${flow.photos.size} — самое старое",style=MaterialTheme.typography.bodySmall)
        if(flow.partialAccess)Text("Android разрешил только часть фотографий. Номера относятся к доступным фото.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        voicePanel()
        if(flow.previewLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
        flow.selected?.let {photo ->
            Text("Фото №${flow.selectedNumber} · ${photo.name}",style=MaterialTheme.typography.titleSmall)
            flow.preview?.let {Image(it.asImageBitmap(),contentDescription="Просмотр фото №${flow.selectedNumber}",modifier=Modifier.fillMaxWidth().height(150.dp).testTag("gallery_preview").pointerInput(flow.selectedNumber,flow.photos.size) {
                var distance=0f
                detectHorizontalDragGestures(onDragStart={distance=0f},onDragCancel={distance=0f},onDragEnd={
                    val current=flow.selectedNumber ?: return@detectHorizontalDragGestures
                    if(flow.photos.size>1 && kotlin.math.abs(distance)>48.dp.toPx()) {
                        val target=current+if(distance<0)1 else -1
                        if(target in 1..flow.photos.size)flow.select(target)
                    }
                }) {change,amount->change.consume();distance+=amount}
            },contentScale=ContentScale.Fit)}
            if(flow.photos.size>1)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={flow.select((flow.selectedNumber ?: 1)-1)},enabled=(flow.selectedNumber ?: 1)>1,modifier=Modifier.weight(1f)){Text("Предыдущее")}
                OutlinedButton(onClick={flow.select((flow.selectedNumber ?: 1)+1)},enabled=(flow.selectedNumber ?: 1)<flow.photos.size,modifier=Modifier.weight(1f)){Text("Следующее")}
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=number,onValueChange={if(it.all(Char::isDigit))number=it},label={Text("Номер фото")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))
            TextButton(onClick={number.toIntOrNull()?.let(flow::select) ?: flow.error("Введи номер фото")}){Text("Показать")}
        }
        flow.message?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onConfirm,enabled=flow.preview!=null && !flow.previewLoading,modifier=Modifier.weight(1f)){Text("Подтвердить")}
            OutlinedButton(onClick=onCancel,modifier=Modifier.weight(1f)){Text("Отмена")}
        }
        if(flow.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(columns=GridCells.Fixed(3),state=grid,modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            itemsIndexed(flow.photos,key={_,photo->photo.id}) {index,photo ->
                Card(Modifier.fillMaxWidth().clickable {flow.select(index+1)},colors=CardDefaults.cardColors(containerColor=if(flow.selectedNumber==index+1)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    GalleryThumbnail(photo)
                    Text("№${index+1}",Modifier.padding(horizontal=6.dp),style=MaterialTheme.typography.labelLarge)
                    Text(SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(Date(photo.chronologicalMs)),Modifier.padding(6.dp),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        TextButton(onClick=onSystemPicker){Text("Системный выбор фото")}
    }
}

@Composable private fun GalleryThumbnail(photo: GalleryPhoto) {
    val context=LocalContext.current
    val image by produceState<Bitmap?>(null,photo.uri) {
        value=withContext(Dispatchers.IO){runCatching {LocalPhotoGallery.preview(context,photo.uri,180)}.getOrNull()}
    }
    Box(Modifier.fillMaxWidth().height(80.dp)) {
        image?.let {Image(it.asImageBitmap(),contentDescription="Фото ${photo.name}",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())}
    }
}
