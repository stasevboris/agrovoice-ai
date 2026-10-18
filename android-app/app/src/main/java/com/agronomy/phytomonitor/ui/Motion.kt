package com.agronomy.phytomonitor.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Durations of the three motion moments of the app; everything else changes instantly. */
object AgroMotion {
    /** Frames on the photo are drawn after an analysis. */
    const val FRAMES_MS = 220

    /** The result block slides in under the photo. */
    const val RESULT_MS = 200

    /** One breath of the ring around the microphone while it listens. */
    const val LISTEN_PULSE_MS = 900
}

/**
 * True when the user switched animations off in the system settings (developer options or
 * accessibility «remove animations»): then frames, results and the microphone ring appear at once.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scale == 0f
    }
}
