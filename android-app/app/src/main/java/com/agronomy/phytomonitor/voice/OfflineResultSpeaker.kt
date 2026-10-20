package com.agronomy.phytomonitor.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** App speech output: bundled Russian voice first, installed offline system voice as fallback. */
class OfflineResultSpeaker(context: Context) {
    private var revision by mutableIntStateOf(0)
    private val output = SpeechOutput(
        NativeSpeechBackend(context.applicationContext),
        SystemSpeechBackend(context.applicationContext),
        AndroidSpeechFocus(context.applicationContext),
        changed = { revision++ },
    )

    val active: Boolean get() = revision.let { output.active }
    val available: Boolean get() = revision.let { output.available }
    val initialized: Boolean get() = revision.let { output.initialized }
    val status: String get() = revision.let { output.status }
    val completion: String get() = revision.let { output.completion }
    val voiceName: String get() = revision.let { output.voiceName }

    fun speak(text: String, volume: Float = 1f): Boolean = output.speak(text, volume)
    fun stop() = output.stop()
    fun close() = output.close()
}
