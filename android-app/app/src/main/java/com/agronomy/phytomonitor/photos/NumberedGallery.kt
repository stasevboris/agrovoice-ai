package com.agronomy.phytomonitor.photos

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.ui.MessageBanner
import com.agronomy.phytomonitor.ui.theme.PhotoBackdrop
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
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(AgroIcons.Back, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text("Галерея · ${flow.photos.size} фото", style = MaterialTheme.typography.titleLarge)
                Text("№1 — самое новое, №${flow.photos.size} — самое старое", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (flow.partialAccess) {
            MessageBanner("Android разрешил только часть фотографий. Номера относятся к доступным фото.", isError = true)
        }
        voicePanel()
        if (flow.previewLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        flow.selected?.let { photo -> SelectedPreview(flow, photo.name) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = number,
                onValueChange = { if (it.all(Char::isDigit)) number = it },
                label = { Text("Номер фото") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = { number.toIntOrNull()?.let(flow::select) ?: flow.error("Введи номер фото") }) { Text("Показать") }
        }
        flow.message?.let { MessageBanner(it, isError = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, enabled = flow.preview != null && !flow.previewLoading, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(AgroIcons.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Подтвердить")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text("Отмена") }
        }
        if (flow.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(flow.photos, key = { _, photo -> photo.id }) { index, photo ->
                GalleryCell(photo, index + 1, selected = flow.selectedNumber == index + 1, onClick = { flow.select(index + 1) })
            }
        }
        TextButton(onClick = onSystemPicker) { Text("Системный выбор фото") }
    }
}

@Composable
private fun SelectedPreview(flow: PhotoFlowViewModel, name: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Фото №${flow.selectedNumber} · $name", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        flow.preview?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "Просмотр фото №${flow.selectedNumber}",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(PhotoBackdrop)
                    .testTag("gallery_preview")
                    .pointerInput(flow.selectedNumber, flow.photos.size) {
                        var distance = 0f
                        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f }, onDragEnd = {
                            val current = flow.selectedNumber ?: return@detectHorizontalDragGestures
                            if (flow.photos.size > 1 && kotlin.math.abs(distance) > 48.dp.toPx()) {
                                val target = current + if (distance < 0) 1 else -1
                                if (target in 1..flow.photos.size) flow.select(target)
                            }
                        }) { change, amount -> change.consume(); distance += amount }
                    },
                contentScale = ContentScale.Fit
            )
        }
        if (flow.photos.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { flow.select((flow.selectedNumber ?: 1) - 1) }, enabled = (flow.selectedNumber ?: 1) > 1, modifier = Modifier.weight(1f)) { Text("Предыдущее") }
                OutlinedButton(onClick = { flow.select((flow.selectedNumber ?: 1) + 1) }, enabled = (flow.selectedNumber ?: 1) < flow.photos.size, modifier = Modifier.weight(1f)) { Text("Следующее") }
            }
        }
    }
}

@Composable
private fun GalleryCell(photo: GalleryPhoto, number: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(if (selected) 3.dp else 0.dp, if (selected) colors.primary else Color.Transparent, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    ) {
        GalleryThumbnail(photo)
        Box(
            Modifier
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) colors.primary else Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("№$number", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        Text(
            SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(photo.chronologicalMs)),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable private fun GalleryThumbnail(photo: GalleryPhoto) {
    val context=LocalContext.current
    val image by produceState<Bitmap?>(null,photo.uri) {
        value=withContext(Dispatchers.IO){runCatching {LocalPhotoGallery.preview(context,photo.uri,180)}.getOrNull()}
    }
    Box(Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {Image(it.asImageBitmap(),contentDescription="Фото ${photo.name}",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())}
    }
}
