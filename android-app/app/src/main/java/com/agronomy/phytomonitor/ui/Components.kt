package com.agronomy.phytomonitor.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.agronomy.phytomonitor.ui.theme.numericCaption
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ui.theme.DetectionAmber
import com.agronomy.phytomonitor.ui.theme.PhotoBackdrop
import java.util.Locale
import kotlin.math.min

/** A softly tinted group used only where the content is a separate task (result, inspection). */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BlockHeader(title, icon, trailing, onHeaderClick)
            content()
        }
    }
}

/** Title line of a block. Used both inside cards and directly on the page background. */
@Composable
fun BlockHeader(
    title: String,
    icon: ImageVector? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Round numbered badge; the same colour as the frames on the photo so rows and frames match. */
@Composable
fun NumberBadge(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(DetectionAmber),
        contentAlignment = Alignment.Center
    ) {
        Text("$number", color = Color.Black, style = MaterialTheme.typography.labelLarge)
    }
}

/** Small rounded label such as «предварительно». */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Info or error line with an icon on a tinted background. */
@Composable
fun MessageBanner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = if (isError) colors.errorContainer else colors.secondaryContainer
    val content = if (isError) colors.onErrorContainer else colors.onSecondaryContainer
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AgroIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Placeholder of the photo area before the first snapshot: the whole first screen is this instruction. */
@Composable
fun EmptyPhoto(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(28.dp)
        ) {
            Icon(
                AgroIcons.Leaf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Сфотографируй один лист крупно",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Хороший свет, без размытия, лист в центре кадра",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

/** Keeps the photo close to its own proportions; a phone photo must not push the result off screen. */
private fun photoAspect(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) {
        return 1f
    }
    // Only absurd panoramas are clamped; ordinary 3:4, 4:3 and 16:9 photos keep their own frame, no letterbox bars.
    return (width.toFloat() / height).coerceIn(0.4f, 2.5f)
}

/**
 * With the photo at most this tall, the first diagnosis stays visible under it on a 360×640 dp phone.
 * The action bar is pinned at the bottom, so everything above it is the working area.
 */
private val PhotoMaxHeight = 340.dp

/**
 * The photo with numbered detection frames drawn over it.
 * While a new analysis runs, a dim layer with a progress line covers the photo instead of a separate card.
 */
@Composable
fun PhotoWithDetections(
    image: Bitmap,
    boxes: List<LeafDetection>,
    showBoxes: Boolean,
    busy: Boolean = false,
    maxHeight: Dp = PhotoMaxHeight,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(boxes) {
        if (boxes.isEmpty() || reducedMotion) {
            reveal.snapTo(1f)
        } else {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(AgroMotion.FRAMES_MS, easing = FastOutSlowInEasing))
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Explicit size: with fillMaxWidth + aspectRatio the photo could grow past its cap and overlap the result.
        // When the cap bites (a tall phone photo), the frame shrinks to the photo's own width instead of letterboxing.
        val aspect = photoAspect(image.width, image.height)
        val height = minOf(maxWidth / aspect, maxHeight)
        Box(
            Modifier
                .align(Alignment.Center)
                .width(height * aspect)
                .height(height)
                .clip(MaterialTheme.shapes.medium)
                .background(PhotoBackdrop)
        ) {
            Image(
                image.asImageBitmap(),
                contentDescription = "Выбранное фото растения",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (showBoxes && !busy) {
                DetectionOverlay(image, boxes, reveal.value)
            }
            if (busy) {
                AnalysisScrim()
            }
        }
    }
}

@Composable
private fun AnalysisScrim() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Анализ фото…", color = Color.White, style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = DetectionAmber,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

/** [progress] 0..1 draws the frames growing from their centres: the eye goes to every leaf once. */
@Composable
private fun DetectionOverlay(image: Bitmap, boxes: List<LeafDetection>, progress: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val scale = min(size.width / image.width, size.height / image.height)
        val offsetX = (size.width - image.width * scale) / 2
        val offsetY = (size.height - image.height * scale) / 2
        val stroke = 3.dp.toPx()
        val badge = 24.dp.toPx()
        val grow = 0.85f + 0.15f * progress
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 14.dp.toPx()
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        boxes.forEachIndexed { index, box ->
            val fullWidth = (box.right - box.left) * scale
            val fullHeight = (box.bottom - box.top) * scale
            val width = fullWidth * grow
            val height = fullHeight * grow
            val left = offsetX + box.left * scale + (fullWidth - width) / 2
            val top = offsetY + box.top * scale + (fullHeight - height) / 2
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.35f * progress),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(stroke + 2.dp.toPx())
            )
            drawRoundRect(
                color = DetectionAmber.copy(alpha = progress),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            val badgeLeft = left.coerceIn(0f, size.width - badge)
            val badgeTop = (top - badge / 2).coerceIn(0f, size.height - badge)
            drawCircle(DetectionAmber.copy(alpha = progress), radius = badge / 2, center = Offset(badgeLeft + badge / 2, badgeTop + badge / 2))
            textPaint.alpha = (255 * progress).toInt()
            val baseline = badgeTop + badge / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            drawContext.canvas.nativeCanvas.drawText("${index + 1}", badgeLeft + badge / 2, baseline, textPaint)
        }
    }
}

/** What TalkBack reads for one result row: «Лист 1: томат, мозаика, уверенность модели 66 процентов». */
fun spokenLeafRow(number: Int, label: String, confidence: Float): String {
    val parsed = parseLeafLabel(label)
    val percent = (confidence.coerceIn(0f, 1f) * 100).toInt()
    val condition = parsed.condition.ifBlank { parsed.crop }.lowercase()
    val crop = parsed.crop.lowercase()
    val what = if (condition == crop) crop else "$crop, $condition"
    return "Лист $number: $what, уверенность модели $percent процентов"
}

/**
 * One detected leaf: number, crop, condition and the model's confidence.
 * The bar is always neutral: its colour never suggests a disease or a share of damage.
 */
@Composable
fun LeafResultRow(
    number: Int,
    label: String,
    confidence: Float,
    prominent: Boolean = false,
    showCrop: Boolean = true,
    onExplain: () -> Unit = {}
) {
    val parsed = parseLeafLabel(label)
    val colors = MaterialTheme.colorScheme
    val conditionColor = when (parsed.state) {
        LeafState.HEALTHY -> colors.primary
        LeafState.ATTENTION -> colors.error
        LeafState.UNKNOWN -> colors.onSurfaceVariant
    }
    val spoken = spokenLeafRow(number, label, confidence)
    Surface(color = colors.surfaceContainerLowest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = if (prominent) 12.dp else 8.dp, bottom = if (prominent) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Badge, names and bar are one TalkBack stop; the «?» stays a separate button.
            Row(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumberBadge(number)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (showCrop) {
                        Text(parsed.crop, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(
                        parsed.condition.ifBlank { parsed.crop },
                        style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        color = conditionColor
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { confidence.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = colors.onSurfaceVariant,
                            trackColor = colors.outlineVariant,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${(confidence.coerceIn(0f, 1f) * 100).toInt()} %",
                            style = numericCaption,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onExplain, modifier = Modifier.size(48.dp)) {
                Icon(
                    AgroIcons.Info,
                    contentDescription = "Что значит уверенность",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Explains what the model's confidence is and is not. Opened from every result row. */
@Composable
fun ConfidenceDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(AgroIcons.Info, contentDescription = null) },
        title = { Text("Уверенность модели") },
        text = {
            Text(
                "Это оценка того, насколько модель уверена в классе листа. " +
                    "Она не означает вероятность болезни и не показывает долю поражённого листа. " +
                    "Результат нужно проверять специалисту."
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Понятно") } }
    )
}

/** Colour of a [Pill] that marks results as preliminary. */
@Composable
fun preliminaryColors(): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return colors.tertiaryContainer to colors.onTertiaryContainer
}

/** One line of a comparison run: model name and the first leaves it found. */
@Composable
fun ComparisonRow(name: String, summary: String, meta: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Medium-emphasis secondary button with an icon and a label, used in the bottom action bar. */
@Composable
fun SecondaryAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        modifier = modifier.height(56.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
}

/** Icon-only action with an accessible name. */
@Composable
fun RoundAction(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(56.dp)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}
