package com.agronomy.phytomonitor.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/** Only installed Russian voices that explicitly work without the network are eligible. */
internal class SystemSpeechBackend(private val context: Context) : SpeechBackend {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var closed = false
    private var utterance = ""
    private var completion: ((Boolean) -> Unit)? = null

    override fun initialize(onReady: (String?) -> Unit) {
        engine = TextToSpeech(context) { code ->
            main.post {
                if (closed) return@post
                val tts = engine
                val voice = runCatching {
                    if (code != TextToSpeech.SUCCESS) null else tts?.voices
                        ?.filter {
                            it.locale.language == "ru" && !it.isNetworkConnectionRequired &&
                                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                        }
                        ?.sortedBy { it.name }
                        ?.firstOrNull { tts.setVoice(it) == TextToSpeech.SUCCESS }
                }.getOrNull()
                tts?.setAudioAttributes(speechAudioAttributes())
                onReady(voice?.name)
            }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) = finish(id, true)
            @Deprecated("Platform callback")
            override fun onError(id: String) = finish(id, false)
            override fun onError(id: String, errorCode: Int) = finish(id, false)
        })
    }

    private fun finish(id: String, success: Boolean) {
        main.post {
            if (!closed && id == utterance) {
                val callback = completion
                completion = null
                utterance = ""
                callback?.invoke(success)
            }
        }
    }

    override fun speak(text: String, volume: Float, onFinished: (Boolean) -> Unit) {
        stop()
        utterance = UUID.randomUUID().toString()
        completion = onFinished
        val parameters = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, utterance) != TextToSpeech.SUCCESS) {
            finish(utterance, false)
        }
    }

    override fun stop() {
        utterance = ""
        completion = null
        engine?.stop()
    }

    override fun close() {
        closed = true
        stop()
        engine?.shutdown()
        engine = null
    }
}
