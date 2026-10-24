package com.agronomy.phytomonitor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.util.Random
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Inference time of candidate ONNX files with the app's session options (4 intra-op threads, CPU).
 * Push models to <external files>/benchmark/ and pass -e models a.onnx,b.onnx. Random input: timing only, no accuracy.
 */
class OnnxBenchmarkDeviceTest {
    @Test
    fun timeCandidateModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification")) { "Isolated verification package only" }
        val folder = File(context.getExternalFilesDir(null), "benchmark")
        val names = requireNotNull(InstrumentationRegistry.getArguments().getString("models")).split(",")
        val runs = InstrumentationRegistry.getArguments().getString("runs")?.toInt() ?: 30
        require(runs in 1..500) { "runs must be between 1 and 500" }
        require(names.isNotEmpty() && names.all { it.matches(Regex("[A-Za-z0-9_.-]+\\.onnx")) })
        val environment = OrtEnvironment.getEnvironment()
        val results = JSONArray()
        for (name in names) {
            val file = File(folder, name)
            check(file.isFile) { "Missing candidate: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                var count = stream.read(buffer)
                while (count >= 0) {
                    digest.update(buffer, 0, count)
                    count = stream.read(buffer)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                environment.createSession(file.absolutePath, options)
            }
            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1e6
            session.use {
                val input = session.inputNames.first()
                val shape = (session.inputInfo.getValue(input).info as ai.onnxruntime.TensorInfo).shape
                require(shape.size == 4 && shape[0] == 1L && shape[1] == 3L &&
                    shape[2] in 1L..1024L && shape[2] == shape[3]) { "Expected a fixed square RGB input" }
                val side = shape[2].toInt()
                val random = Random(20261020)
                val values = FloatArray(3 * side * side) { random.nextFloat() }
                val times = mutableListOf<Double>()
                var outputShape = ""
                repeat(5 + runs) { index ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape).use { tensor ->
                        val started = SystemClock.elapsedRealtimeNanos()
                        session.run(mapOf(input to tensor)).use { output ->
                            if (index >= 5) times += (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                            outputShape = (output[0].info as ai.onnxruntime.TensorInfo).shape.joinToString("x")
                        }
                    }
                }
                val sorted = times.sorted()
                val median = (sorted[(runs - 1) / 2] + sorted[runs / 2]) / 2
                results.put(JSONObject().put("model", name).put("sha256", sha256)
                    .put("bytes", file.length()).put("session_load_ms", loadMs)
                    .put("input_shape", shape.joinToString("x"))
                    .put("output_shape", outputShape).put("runs", times.size)
                    .put("times_ms", JSONArray(times))
                    .put("median_ms", median).put("p90_ms", sorted[ceil(runs * 0.9).toInt() - 1])
                    .put("min_ms", sorted.first())
                    .put("process_pss_kib", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss))
            }
        }
        File(folder, "benchmark-report.json").writeText(JSONObject().put("results", results)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("package", context.packageName)
            .put("ort_version", environment.version)
            .put("created_epoch_ms", System.currentTimeMillis())
            .put("percentile_method", "nearest rank; median averages the two middle values")
            .put("memory_scope", "Whole test process PSS while the session is open; not model-only memory")
            .put("method", "ORT Java CPU, 4 intra-op threads, 5 warm-up runs, random fixed RGB input from session shape; preprocessing and decoding excluded")
            .toString(2))
    }
}
