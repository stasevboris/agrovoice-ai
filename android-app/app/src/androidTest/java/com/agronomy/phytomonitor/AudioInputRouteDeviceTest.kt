package com.agronomy.phytomonitor

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.voice.AudioInputRoute
import com.agronomy.phytomonitor.voice.InputKind
import com.agronomy.phytomonitor.voice.InputRouteSession
import com.agronomy.phytomonitor.voice.OfflineVoiceController
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Future

/** Short microphone reads are discarded in memory: no audio files, recognized words or headset claims. */
@RunWith(AndroidJUnit4::class)
class AudioInputRouteDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))

    @Test
    fun deferredStopActionPreventsAutomaticRecognitionRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var controller: OfflineVoiceController? = null
        val completed = CountDownLatch(1)
        val readyInCallback = AtomicBoolean()
        try {
            instrumentation.runOnMainSync {
                val current = OfflineVoiceController(context)
                controller = current
                current.stop {
                    readyInCallback.set(current.readyToStart)
                    completed.countDown()
                }
                assertFalse(current.readyToStart)
                // Simulates Compose's continuous-listening restart before the stop callback.
                // The gate must reject this before loading a model or opening any microphone.
                current.start(grammar = listOf("[unk]"))
                assertFalse(current.preparing)
                assertFalse(current.active)
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(readyInCallback.get())
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
        }
    }

    @Test
    fun actualActivityStopButtonsHeadsetAndExitCancelPendingSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (action in listOf("button", "voice", "headset", "exit")) {
            withVoiceUiSuspended { scenario ->
                val speeches = AtomicInteger()
                var released: Future<*>? = null
                scenario.onActivity { activity ->
                    released = activity.voiceController.stop { speeches.incrementAndGet() }
                    assertTrue("Pending speech must be shown as stoppable", activity.currentState().speaking)
                    when (action) {
                        "headset" -> activity.headsetPress()
                        "exit" -> activity.actions.dispatch(ActionRequest(VoiceAction.EXIT, source = ActionSource.BUTTON))
                        else -> activity.actions.dispatch(ActionRequest(VoiceAction.SILENCE,
                            source = if (action == "button") ActionSource.BUTTON else ActionSource.VOICE))
                    }
                }
                requireNotNull(released).get(5, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                assertEquals("Deferred speech survived $action", 0, speeches.get())
            }
        }
    }

    private fun withVoiceUiSuspended(action: (ActivityScenario<FieldDemoActivity>) -> Unit) {
        val settings = SettingsStore.get(context)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        try {
            runBlocking {
                settings.set(Keys.continuous, false).join()
                settings.set(Keys.voiceEnabled, false).join()
                settings.state.filterNotNull().first { !it.continuous && !it.voiceEnabled }
            }
            ActivityScenario.launch(FieldDemoActivity::class.java).use(action)
        } finally {
            runBlocking {
                settings.set(Keys.continuous, previous.continuous).join()
                settings.set(Keys.voiceEnabled, previous.voiceEnabled).join()
            }
        }
    }

    @Test
    fun phoneFallbackStopAndRestartRestoreAudioSettings() {
        assumeTrue("RECORD_AUDIO must already be granted by the authorized operator",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val probe = AudioInputRoute(context)
        val devices = probe.devices()
        probe.close()
        assumeTrue("This case is specifically for a phone without a headset", devices.none { it.kind != InputKind.PHONE })
        assumeTrue("Do not disturb an existing call", audio.mode == AudioManager.MODE_NORMAL)
        val before = snapshot()
        val observed = JSONArray()
        val settings = SettingsStore.get(context)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        try {
            runBlocking {
                settings.set(Keys.continuous, false).join()
                settings.set(Keys.voiceEnabled, false).join()
                settings.state.filterNotNull().first { !it.voiceEnabled && !it.continuous }
            }
            // Android 15+ permits audio focus only for a foreground app. Disable the real ASR UI
            // while the standalone recorder test owns the microphone; no recognized words are produced.
            ActivityScenario.launch(FieldDemoActivity::class.java).use {
                repeat(2) {
                    val interrupted = AtomicReference<String?>(null)
                    val session = InputRouteSession(AudioInputRoute(context) { interrupted.set(it) })
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val input = session.open { false }
                        assertEquals(InputKind.PHONE, input.device.kind)
                        observed.put(JSONObject().put("input_id", input.device.id).put("kind", input.device.kind.name))
                        val read = executor.submit<Int> { input.stream.read(ByteArray(6400)) }
                        assertTrue("No PCM arrived from the routed recorder", read.get(5, TimeUnit.SECONDS) > 0)
                        val pending = executor.submit<Int> { input.stream.read(ByteArray(6400)) }
                        session.stopCapture()
                        // A read racing stop may complete its last block or report EOF; it must terminate.
                        pending.get(3, TimeUnit.SECONDS)
                        session.close()
                        session.close()
                        assertNull(interrupted.get())
                        assertEquals(before.toString(), snapshot().toString())
                    } finally {
                        session.close()
                        executor.shutdownNow()
                    }
                }
            }
            val cancelled = InputRouteSession(AudioInputRoute(context))
            assertThrows(CancellationException::class.java) { cancelled.open { true } }
            cancelled.close()
            assertEquals(before.toString(), snapshot().toString())
        } finally {
            runBlocking {
                settings.set(Keys.continuous, previous.continuous).join()
                settings.set(Keys.voiceEnabled, previous.voiceEnabled).join()
            }
        }
        File(context.getExternalFilesDir(null), "audio_route_device_report.json").writeText(JSONObject()
            .put("status", "passed").put("before", before).put("after", snapshot())
            .put("observed_inputs", observed).put("audio_saved", false)
            .put("scope", "Actual recorder route and discarded PCM on phone without headset; not live command accuracy, headset audibility, Bluetooth or USB validation")
            .toString(2))
    }

    @Suppress("DEPRECATION")
    private fun snapshot(): JSONObject = JSONObject().put("mode", audio.mode)
        .put("sco_on", audio.isBluetoothScoOn)
        .put("communication_device", if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.id else JSONObject.NULL)
}
