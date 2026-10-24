package com.agronomy.phytomonitor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Real source crops, desktop reference logits; isolated app only, no journal mutation. */
class VerifierParityDeviceTest {
    @Test
    fun comparePreparedCropsWithDesktopReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        val root = File(context.getExternalFilesDir(null), "benchmark")
        val fixture = File(root, "rejection_parity_20261024")
        val manifest = JSONObject(File(fixture, "reference.json").readText())
        val model = File(root, "dino_verifier_20261024.onnx")
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        check(sha == manifest.getString("model_sha256"))
        val samples = manifest.getJSONArray("samples")
        val results = JSONArray()
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            environment.createSession(model.absolutePath, options).use { session ->
                repeat(samples.length()) { index ->
                    val sample = samples.getJSONObject(index)
                    val bytes = File(fixture, "input_$index.bin").readBytes()
                    check(bytes.size == 3 * 224 * 224 * 4)
                    val values = FloatArray(3 * 224 * 224)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
                    check(values.all { it.isFinite() })
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 224, 224)).use { tensor ->
                        session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            val actual = (output[0].value as Array<FloatArray>)[0]
                            val expected = sample.getJSONArray("logits")
                            check(actual.size == 16 && expected.length() == 16)
                            var maximum = 0.0
                            actual.forEachIndexed { position, value ->
                                check(value.isFinite())
                                maximum = maxOf(maximum, abs(value.toDouble() - expected.getDouble(position)))
                            }
                            check(maximum <= 0.001) { "Phone/desktop logits differ: $maximum" }
                            results.put(JSONObject().put("index", index).put("max_abs_diff", maximum))
                        }
                    }
                }
            }
        }
        File(fixture, "phone-parity-report.json").writeText(
            JSONObject().put("model_sha256", sha).put("samples", results)
                .put("tolerance_abs", 0.001).put("status", "passed")
                .put("method", "Precomputed real-crop tensors, desktop ORT logits versus Android ORT; camera crop path excluded")
                .toString(2)
        )
    }
}
