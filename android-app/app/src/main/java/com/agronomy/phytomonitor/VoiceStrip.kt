package com.agronomy.phytomonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.photos.PhotoFlowViewModel
import com.agronomy.phytomonitor.ui.AgroIcons
import com.agronomy.phytomonitor.voice.OfflineVoiceController

/**
 * The microphone strip used inside the camera and the numbered gallery.
 * Drawn on a dark translucent background so the viewfinder stays the brightest thing on screen.
 */
@Composable
internal fun VoiceStrip(
    voice: OfflineVoiceController,
    flow: PhotoFlowViewModel,
    onVoice: () -> Unit,
    onContinuous: (Boolean) -> Unit,
    hint: String,
    onDark: Boolean = false
) {
    val listening = voice.active || flow.dialogVoice || flow.continuousVoice
    val text = when {
        voice.heard.isNotBlank() -> "Услышал: ${voice.heard}"
        listening -> voice.status
        else -> hint
    }
    val content = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (onDark) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (onDark) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = onVoice,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (listening) Modifier.background(MaterialTheme.colorScheme.tertiary, CircleShape) else Modifier)
            ) {
                Icon(
                    if (listening) AgroIcons.Stop else AgroIcons.Mic,
                    contentDescription = if (listening) "Остановить микрофон" else "Голосовая команда",
                    tint = if (listening) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (listening) MaterialTheme.colorScheme.primary else mutedColor,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { flow.helpOpen = true }) {
                Text("Фразы", color = if (onDark) Color.White else MaterialTheme.colorScheme.primary)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .toggleable(value = flow.continuousVoice, role = Role.Switch, onValueChange = onContinuous)
                .padding(horizontal = 2.dp)
                .testTag("continuous_voice"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Постоянно слушать",
                style = MaterialTheme.typography.bodySmall,
                color = content,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = flow.continuousVoice, onCheckedChange = null)
        }
    }
}
