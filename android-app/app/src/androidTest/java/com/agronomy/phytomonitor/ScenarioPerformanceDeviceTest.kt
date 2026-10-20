package com.agronomy.phytomonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceAction
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real camera and inference, unlabelled scene. Timings exclude human speech and reaction time. */
class ScenarioPerformanceDeviceTest {
    @Test fun firstActivityAndRepeatedCameraToSavedInspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val settings = SettingsStore.get(context)
        val previous = runBlocking { settings.state.filterNotNull().first() }
        val samples = JSONArray()
        val output = File(context.getExternalFilesDir(null), "scenario-performance-${System.currentTimeMillis()}.json")
        try {
            runBlocking {
                settings.set(Keys.voiceEnabled, false).join()
                settings.set(Keys.continuous, false).join()
            }
            val launched = SystemClock.elapsedRealtime()
            ActivityScenario.launch(FieldDemoActivity::class.java).use { scenario ->
                await(scenario) { !it.currentState().busy }
                val activityReady = SystemClock.elapsedRealtime()
                repeat(3) { index ->
                    val sample = JSONObject().put("run", index)
                        .put("kind", if (index == 0) "first_activity_in_fresh_instrumentation_process" else "repeat_in_same_activity")
                        .put("before", resources(context))
                    samples.put(sample)
                    val opened = SystemClock.elapsedRealtime()
                    dispatch(scenario, VoiceAction.CAMERA)
                    await(scenario) { it.cameraModel.state.value.previewStreaming }
                    val ready = SystemClock.elapsedRealtime()
                    dispatch(scenario, VoiceAction.CONFIRM)
                    await(scenario) { it.cameraModel.state.value.capturing }
                    await(scenario) { !it.cameraModel.state.value.capturing }
                    val jpegSaved = SystemClock.elapsedRealtime()
                    await(scenario) { !it.currentState().busy && it.currentState().screen != Screen.CAMERA && it.fieldModel.bitmap != null }
                    val captured = SystemClock.elapsedRealtime()
                    dispatch(scenario, VoiceAction.ANALYZE)
                    await(scenario) { !it.currentState().busy && it.fieldModel.result != null }
                    val analyzed = SystemClock.elapsedRealtime()
                    var report = ""
                    scenario.onActivity {
                        report = requireNotNull(it.fieldModel.reportName)
                        sample.put("model_id", it.fieldModel.result!!.model.id)
                            .put("model_sha256", it.fieldModel.result!!.model.sha256)
                            .put("reported_inference_ms", it.fieldModel.result!!.inferenceMs)
                    }
                    dispatch(scenario, VoiceAction.SAVE_INSPECTION)
                    await(scenario) { !it.currentState().busy && it.fieldModel.inspectionSaved }
                    val saved = SystemClock.elapsedRealtime()
                    assertNotNull(com.agronomy.phytomonitor.journal.JournalDatabase.get(context).journal().inspectionByReport(report))
                    sample.put("activity_launch_to_ready_ms", if (index == 0) activityReady-launched else JSONObject.NULL)
                        .put("camera_open_to_first_stream_ms", ready-opened)
                        .put("shutter_to_imported_photo_ms", captured-ready)
                        .put("shutter_to_saved_jpeg_ms", jpegSaved-ready)
                        .put("saved_jpeg_to_decoded_photo_ms", captured-jpegSaved)
                        .put("analysis_action_to_result_ms", analyzed-captured)
                        .put("save_action_to_confirmed_room_ms", saved-analyzed)
                        .put("camera_open_to_saved_ms", saved-opened)
                        .put("activity_launch_to_saved_ms", if (index == 0) saved-launched else JSONObject.NULL)
                        .put("after", resources(context))
                    var count = 0
                    scenario.onActivity { count = it.cameraModel.state.value.qualitySamples; assertFalse(it.cameraModel.state.value.previewStreaming) }
                    SystemClock.sleep(500)
                    scenario.onActivity { assertEquals(count, it.cameraModel.state.value.qualitySamples) }
                    sample.put("preview_analysis_stops_after_exit", true)
                    write(output, samples, context)
                    dispatch(scenario, VoiceAction.BACK)
                }
            }
        } finally {
            runBlocking {
                settings.set(Keys.continuous, previous.continuous).join()
                settings.set(Keys.voiceEnabled, previous.voiceEnabled).join()
            }
            write(output, samples, context)
        }
    }

    private fun dispatch(scenario: ActivityScenario<FieldDemoActivity>, action: VoiceAction) {
        scenario.onActivity { it.actions.dispatch(ActionRequest(action, source = ActionSource.BUTTON)) }
    }

    private fun await(scenario: ActivityScenario<FieldDemoActivity>, condition: (FieldDemoActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+90_000
        var diagnostic = ""
        while (true) {
            var ready = false
            scenario.onActivity {
                ready = condition(it)
                diagnostic = "${it.currentState().screen}; ${it.fieldModel.message}; ${it.cameraModel.state.value.message}"
            }
            if (ready) return
            check(SystemClock.elapsedRealtime()<deadline) { "Scenario timed out: $diagnostic" }
            SystemClock.sleep(20)
        }
    }

    private fun resources(context: Context): JSONObject {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = context.getSystemService(BatteryManager::class.java)
        val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return JSONObject().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("process_pss_kib", memory.totalPss)
            .put("battery_temperature_tenths_c", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE))
            .put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("battery_scale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            .put("charge_counter_uah", if (charge == Int.MIN_VALUE) JSONObject.NULL else charge)
    }

    private fun write(output: File, samples: JSONArray, context: Context) {
        output.writeText(JSONObject().put("package", context.packageName).put("samples", samples)
            .put("boundaries", "elapsedRealtime; ActivityScenario launch until idle; CAMERA until first preview stream; CONFIRM until imported bitmap (split at CameraX onImageSaved: capturing flag false); ANALYZE until result; SAVE until Room confirmation")
            .put("limits", "Instrumentation overhead and polling included; first Activity is not raw OS process startup. Camera scene unlabelled, no diagnosis truth. Charging battery deltas do not measure discharge; battery sensor is not device-surface temperature. Generated captures remain in isolated journal/gallery for traceability; images not exported by this report.")
            .toString(2), Charsets.UTF_8)
    }
}
