package com.agronomy.phytomonitor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ui.theme.DetectionAmber
import com.agronomy.phytomonitor.ui.theme.PhotoBackdrop
import java.util.Locale
import kotlin.math.min

/** A titled card used for every block of the main screen. */
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val header = if (onHeaderClick == null) Modifier else Modifier.clickable(onClick = onHeaderClick)
            Row(header, verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
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

/** Small rounded label such as «офлайн» or «предварительно». */
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
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AgroIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Placeholder of the photo area before the first snapshot. */
@Composable
fun EmptyPhoto(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(
                AgroIcons.Leaf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Сфотографируй один лист крупно",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Хороший свет, без размытия, лист в центре кадра",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** The photo with numbered detection frames drawn over it. */
@Composable
fun PhotoWithDetections(image: Bitmap, boxes: List<LeafDetection>, showBoxes: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(MaterialTheme.shapes.large)
            .background(PhotoBackdrop)
    ) {
        Image(
            image.asImageBitmap(),
            contentDescription = "Выбранное фото растения",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        if (showBoxes) {
            DetectionOverlay(image, boxes)
        }
    }
}

@Composable
private fun DetectionOverlay(image: Bitmap, boxes: List<LeafDetection>) {
    Canvas(Modifier.fillMaxSize()) {
        val scale = min(size.width / image.width, size.height / image.height)
        val offsetX = (size.width - image.width * scale) / 2
        val offsetY = (size.height - image.height * scale) / 2
        val stroke = 3.dp.toPx()
        val badge = 24.dp.toPx()
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 14.dp.toPx()
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        boxes.forEachIndexed { index, box ->
            val left = offsetX + box.left * scale
            val top = offsetY + box.top * scale
            val width = (box.right - box.left) * scale
            val height = (box.bottom - box.top) * scale
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(stroke + 2.dp.toPx())
            )
            drawRoundRect(
                color = DetectionAmber,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            val badgeLeft = left.coerceIn(0f, size.width - badge)
            val badgeTop = (top - badge / 2).coerceIn(0f, size.height - badge)
            drawCircle(DetectionAmber, radius = badge / 2, center = Offset(badgeLeft + badge / 2, badgeTop + badge / 2))
            val baseline = badgeTop + badge / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            drawContext.canvas.nativeCanvas.drawText("${index + 1}", badgeLeft + badge / 2, baseline, textPaint)
        }
    }
}

/** One detected leaf: number, crop, condition and the model's confidence as a bar. */
@Composable
fun LeafResultRow(number: Int, label: String, confidence: Float) {
    val parsed = parseLeafLabel(label)
    val colors = MaterialTheme.colorScheme
    val accent = when (parsed.state) {
        LeafState.HEALTHY -> colors.primary
        LeafState.ATTENTION -> colors.tertiary
        LeafState.UNKNOWN -> colors.outline
    }
    Surface(color = colors.surfaceContainerLowest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberBadge(number)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(parsed.crop, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(
                    parsed.condition.ifBlank { parsed.crop },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { confidence.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = accent,
                        trackColor = colors.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "уверенность ${String.format(Locale.US, "%.0f", confidence * 100)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Colour of a [Pill] that marks results as preliminary. */
@Composable
fun preliminaryColors(): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return colors.tertiaryContainer to colors.onTertiaryContainer
}
