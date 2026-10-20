package com.agronomy.phytomonitor.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.actions.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DeferredSpeechCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun buttonAndVoiceSilenceCancelSpeechBeforeMicrophoneReleaseCallback() {
        for (source in listOf(ActionSource.BUTTON, ActionSource.VOICE)) {
            val controller = OfflineVoiceController(context)
            try {
                var speeches = 0
                val released = requireNotNull(controller.stop { speeches++ })
                val actions = ActionDispatcher(object : ActionPort {
                    override fun state() = AppState(speaking = controller.hasPendingStoppedAction)
                    override fun perform(operation: Operation) {
                        if (operation == Operation.StopSpeech) controller.cancelStoppedAction()
                    }
                    override fun tell(text: String, isError: Boolean, request: ActionRequest?) = Unit
                })
                assertTrue(actions.dispatch(ActionRequest(VoiceAction.SILENCE, source = source)) is Verdict.Run)
                released.get(5, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, speeches)
                assertFalse(controller.hasPendingStoppedAction)
                assertTrue(controller.readyToStart)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun headsetStopCancelsPendingSpeechEvenBeforeTtsIsActive() {
        val controller = OfflineVoiceController(context)
        try {
            var speeches = 0
            val released = requireNotNull(controller.stop { speeches++ })
            val action = HeadsetButtonPolicy.press(Screen.MAIN, controller.hasPendingStoppedAction, false, false)
            assertEquals(HeadsetPress.STOP_SPEECH, action)
            if (action == HeadsetPress.STOP_SPEECH) controller.cancelStoppedAction()
            released.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, speeches)
        } finally {
            controller.close()
        }
    }

    @Test
    fun backgroundStopAndCloseInvalidatePendingSpeech() {
        for (close in listOf(false, true)) {
            val controller = OfflineVoiceController(context)
            var speeches = 0
            val released = requireNotNull(controller.stop { speeches++ })
            if (close) controller.close() else controller.stop()
            released.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, speeches)
            controller.close()
        }
    }
}
