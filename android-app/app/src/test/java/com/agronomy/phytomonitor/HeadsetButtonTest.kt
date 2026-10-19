package com.agronomy.phytomonitor

import android.view.KeyEvent
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.voice.HeadsetButtonPolicy
import com.agronomy.phytomonitor.voice.HeadsetPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-01: the meaning of one headset press in every situation of the hands-free cycle. */
class HeadsetButtonTest {
    private fun press(screen: Screen, speaking: Boolean = false, listening: Boolean = false, followUp: Boolean = false) =
        HeadsetButtonPolicy.press(screen, speaking, listening, followUp)

    @Test
    fun theButtonFollowsTheCycle() {
        assertEquals(HeadsetPress.START_LISTENING, press(Screen.MAIN))
        assertEquals(HeadsetPress.STOP_LISTENING, press(Screen.MAIN, listening = true))
        assertEquals(HeadsetPress.TAKE_SHOT, press(Screen.CAMERA, listening = true))
        assertEquals(HeadsetPress.STOP_SPEECH, press(Screen.MAIN, speaking = true, followUp = true))
        assertEquals(HeadsetPress.ANSWER_YES, press(Screen.MAIN, listening = true, followUp = true))
        assertEquals(HeadsetPress.START_LISTENING, press(Screen.GALLERY))
    }

    @Test
    fun dictationIsNotInterruptedByTheButton() {
        assertEquals(HeadsetPress.NONE, press(Screen.NOTE, listening = true))
    }

    @Test
    fun wiredAndBluetoothKeysAreRecognised() {
        assertTrue(KeyEvent.KEYCODE_HEADSETHOOK in HeadsetButtonPolicy.keys)
        assertTrue(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE in HeadsetButtonPolicy.keys)
    }
}
