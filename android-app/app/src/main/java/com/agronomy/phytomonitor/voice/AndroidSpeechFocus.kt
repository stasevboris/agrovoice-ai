package com.agronomy.phytomonitor.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

internal class AndroidSpeechFocus(context: Context) : SpeechFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var generation = 0L

    override fun acquire(onLost: () -> Unit): Boolean {
        release()
        val current = generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAudioAttributes())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && current == generation) onLost()
            }, Handler(Looper.getMainLooper()))
            .build()
        val granted = audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = next
        return granted
    }

    override fun release() {
        generation++
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}
