package com.agronomy.phytomonitor.voice

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.agronomy.phytomonitor.actions.Screen

/** What one press of the headset button means now. Pure Kotlin, tested on the JVM. */
enum class HeadsetPress { TAKE_SHOT, ANSWER_YES, STOP_SPEECH, STOP_LISTENING, START_LISTENING, NONE }

object HeadsetButtonPolicy {
    /** Keys sent by wired and Bluetooth headsets for their single button. */
    val keys = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY)

    /**
     * Speech first: a press while the app talks silences it. In the camera the button is the shutter;
     * after «Сохранить осмотр?» it answers «да». Otherwise it starts or stops listening.
     */
    fun press(screen: Screen, speaking: Boolean, listening: Boolean, followUp: Boolean): HeadsetPress = when {
        speaking -> HeadsetPress.STOP_SPEECH
        screen == Screen.CAMERA -> HeadsetPress.TAKE_SHOT
        followUp && screen == Screen.MAIN -> HeadsetPress.ANSWER_YES
        screen == Screen.NOTE -> HeadsetPress.NONE
        listening -> HeadsetPress.STOP_LISTENING
        else -> HeadsetPress.START_LISTENING
    }
}

/**
 * V-01: receives the headset button while the app is in the foreground. Platform MediaSession, no extra
 * library. The button does not select a microphone; AudioInputRoute separately requests and checks
 * the actual AudioRecord input for each listening session.
 */
class HeadsetButton(context: Context, private val onPress: () -> Unit) {
    private val session = MediaSession(context.applicationContext, "AgroVoice")

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (event.keyCode !in HeadsetButtonPolicy.keys) {
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    onPress()
                }
                return true
            }
        })
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY)
            .setState(PlaybackState.STATE_PAUSED, 0, 1f)
            .build()
        session.setPlaybackState(state)
    }

    fun setActive(active: Boolean) {
        session.isActive = active
    }

    fun release() {
        session.release()
    }
}
