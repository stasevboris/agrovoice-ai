package com.agronomy.phytomonitor.photos

import com.agronomy.phytomonitor.actions.*
import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.*
import org.junit.Test

class CameraStateTest {
    @Test
    fun shutterRequestIsConsumedOnceAndCannotRepeatDuringCapture() {
        val model = CameraViewModel()
        model.open(true)
        assertTrue(model.consumeAutoShot())
        assertFalse(model.consumeAutoShot())
        model.capturing(true)
        model.requestShot()
        assertFalse(model.state.value.shotRequested)
        model.close()
        assertEquals(CameraState(), model.state.value)
    }

    @Test
    fun backClosesHelpBeforeItsUnderlyingDestination() {
        val back = ActionRequest(VoiceAction.BACK, source = ActionSource.VOICE)
        for (screen in listOf(Screen.CAMERA, Screen.GALLERY, Screen.JOURNAL, Screen.PLOTS, Screen.SETTINGS)) {
            assertEquals(Verdict.Run(listOf(Operation.ShowHelp(false))),
                ActionPolicy.resolve(back, AppState(screen = screen, helpOpen = true)))
        }
    }
}
