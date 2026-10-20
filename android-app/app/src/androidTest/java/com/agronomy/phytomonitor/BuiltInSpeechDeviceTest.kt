package com.agronomy.phytomonitor

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.voice.AndroidSpeechFocus
import com.agronomy.phytomonitor.voice.NativeSpeechBackend
import com.agronomy.phytomonitor.voice.SpeechBackend
import com.agronomy.phytomonitor.voice.SpeechOutput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run only in the isolated verification app. This cannot establish human intelligibility. */
@RunWith(AndroidJUnit4::class)
class BuiltInSpeechDeviceTest {
    @Test fun builtInPlaybackAndCancellationWithoutSystemVoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        val native = NativeSpeechBackend(context)
        val missingSystem = object : SpeechBackend {
            override fun initialize(onReady: (String?) -> Unit) = onReady(null)
            override fun speak(text: String, volume: Float, onFinished: (Boolean) -> Unit) {
                error("System voice must not be used")
            }
            override fun stop() = Unit
            override fun close() = Unit
        }
        lateinit var output: SpeechOutput
        instrumentation.runOnMainSync { output = SpeechOutput(native, missingSystem, AndroidSpeechFocus(context)) }
        fun await(timeout: Long, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                SystemClock.sleep(50)
            }
            error("Timed out waiting for speech state")
        }
        try {
            await(90_000) { output.initialized }
            instrumentation.runOnMainSync {
                assertTrue(output.status, output.available)
                assertEquals("Denis (встроенный)", output.voiceName)
                assertTrue(output.speak("Осмотр сохранён на устройстве.", volume = 0.15f))
                assertTrue(output.active)
            }
            await(60_000) { !output.active }
            instrumentation.runOnMainSync { assertEquals(output.status, "done", output.completion) }
            val playback = requireNotNull(native.lastPlayback)
            assertTrue(playback.generatedSamples > 0)
            assertTrue(playback.playedSamples >= playback.generatedSamples)
            assertTrue(playback.peak > 0f)
            instrumentation.runOnMainSync {
                assertTrue(output.speak("Осмотр сохранён. ".repeat(30), volume = 0.15f))
            }
            await(60_000) { (native.lastPlayback?.generatedSamples ?: 0) > 0 }
            val beforeCancel = requireNotNull(native.lastPlayback)
            instrumentation.runOnMainSync {
                assertTrue(output.active)
                output.stop()
                assertFalse(output.active)
            }
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync {
                assertFalse(output.active)
                assertEquals("stopped", output.completion)
            }
            val folder = File(context.getExternalFilesDir(null), "built-in-tts").apply { mkdirs() }
            File(folder, "report.json").writeText(JSONObject()
                .put("model", "vits-piper-ru_RU-denis-medium-int8")
                .put("voice", "Denis")
                .put("generated_samples", playback.generatedSamples)
                .put("played_samples", playback.playedSamples)
                .put("sample_rate", playback.sampleRate)
                .put("peak", playback.peak.toDouble())
                .put("system_voice_available", false)
                .put("cancellation", "passed")
                .put("samples_before_cancel", beforeCancel.generatedSamples)
                .put("audio_track_drain", "passed")
                .put("human_listening", false).toString(2), Charsets.UTF_8)
        } finally {
            instrumentation.runOnMainSync { output.close() }
        }
    }
}
