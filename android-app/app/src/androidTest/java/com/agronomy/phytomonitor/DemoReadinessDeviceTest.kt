package com.agronomy.phytomonitor

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the installed target's own detector without UI actions or journal/settings writes. */
class DemoReadinessDeviceTest {
    @Test
    fun inspectPreparedImagesWithInstalledDetector() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit read-only detector diagnostic", arguments.getString("allowMainDiagnostic") == "true")
        val folder = File(context.getExternalFilesDir(null), "demo-readiness-20261028")
        val names = requireNotNull(arguments.getString("images")).split(",")
        require(names.all { it.matches(Regex("[A-Za-z0-9_.-]+\\.jpg")) })
        val loader = context.classLoader
        val detectorClass = loader.loadClass("com.agronomy.phytomonitor.ml.FieldDetector")
        val detector = detectorClass.getConstructor(Context::class.java).newInstance(context)
        val photoClass = loader.loadClass("com.agronomy.phytomonitor.ml.PhotoFiles")
        val photoFiles = photoClass.getField("INSTANCE").get(null)
        val decode = photoClass.getMethod("decode", File::class.java)
        val detect = detectorClass.methods.single { it.name == "detect" }
        val results = JSONArray()
        try {
            detectorClass.getMethod("initialize").invoke(detector)
            val models = detectorClass.getMethod("getModels").invoke(detector) as List<*>
            val requested = arguments.getString("modelId") ?: "default"
            val selectedId = if (requested == "default") {
                detectorClass.getMethod("getDefaultModelId").invoke(detector) as String
            } else requested
            val model = models.single {
                it!!.javaClass.getMethod("getId").invoke(it) == selectedId
            }!!
            if (arguments.getString("requireVerifier") == "true") {
                check(model.javaClass.getMethod("getVerifier").invoke(model) != null) {
                    "The default model bypasses the required verifier"
                }
            }
            for (name in names) {
                val file = File(folder, name)
                check(file.isFile)
                val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                val bitmap = decode.invoke(photoFiles, file) as Bitmap
                try {
                    val parameters = arrayOfNulls<Any>(detect.parameterCount)
                    parameters[0] = bitmap
                    parameters[1] = model
                    parameters[2] = "all"
                    parameters[3] = 0.25f
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = detect.invoke(detector, *parameters)!!
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    val actualModel = result.javaClass.getMethod("getModel").invoke(result)
                    @Suppress("UNCHECKED_CAST")
                    val labels = actualModel.javaClass.getMethod("getLabels").invoke(actualModel) as List<String>
                    val boxes = result.javaClass.getMethod("getBoxes").invoke(result) as List<*>
                    val detections = JSONArray()
                    val spokenLabels = mutableListOf<String>()
                    for (box in boxes) {
                        box!!
                        val classId = box.javaClass.getMethod("getClassId").invoke(box) as Int
                        val confidence = box.javaClass.getMethod("getConfidence").invoke(box)
                        spokenLabels += labels[classId]
                        detections.put(JSONObject().put("class_id", classId)
                            .put("label", labels[classId]).put("score", confidence))
                    }
                    val quality = result.javaClass.getMethod("getPhotoQuality").invoke(result)
                    val speechClass = loader.loadClass("com.agronomy.phytomonitor.AnalysisAssessmentKt")
                    val speech = speechClass.methods.single { it.name == "spokenAnalysis" }
                        .invoke(null, spokenLabels, quality) as String
                    val verification = result.javaClass.methods.firstOrNull { it.name == "getVerification" }
                        ?.invoke(result)
                    val verificationJson = verification?.javaClass?.getMethod("toJson")?.invoke(verification)
                    val verificationMessage = verification?.javaClass?.getMethod("message")?.invoke(verification)
                    results.put(JSONObject().put("file", name).put("sha256", sha)
                        .put("width", bitmap.width).put("height", bitmap.height)
                        .put("model_id", actualModel.javaClass.getMethod("getId").invoke(actualModel))
                        .put("model_sha256", actualModel.javaClass.getMethod("getSha256").invoke(actualModel))
                        .put("elapsed_ms", elapsedMs).put("detections", detections)
                        .put("base_spoken_analysis", speech)
                        .put("verification", verificationJson ?: JSONObject.NULL)
                        .put("verification_message", verificationMessage ?: JSONObject.NULL))
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            detectorClass.getMethod("close").invoke(detector)
        }
        File(folder, "installed-detector-report.json").writeText(
            JSONObject().put("package", context.packageName)
                .put("method", "Installed target FieldDetector and PhotoFiles via reflection; all crops, threshold 0.25; no UI or microphone test")
                .put("results", results).toString(2)
        )
    }
}
