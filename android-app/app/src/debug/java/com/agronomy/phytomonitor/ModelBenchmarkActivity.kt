package com.agronomy.phytomonitor

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.ml.PhotoFiles

/** Debug-only, launched through ADB. Models and public validation tensors stay on-device. */
class ModelBenchmarkActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { textSize = 18f; setPadding(32, 48, 32, 32) }
        setContentView(ScrollView(this).apply { addView(status) })
        status.text = "AgroVoice: проверка модели на телефоне…"
        val label = intent.getStringExtra("model") ?: "yolo11n_416"
        require(label.matches(Regex("[a-zA-Z0-9_]+")))
        Thread {
            try {
                val photo=intent.getStringExtra("photo")
                val report = if(photo!=null) benchmarkPhoto(photo) else benchmark(label)
                File(getExternalFilesDir(null), "benchmark/$label.json").writeText(report.toString(2))
                runOnUiThread { status.text = "Проверка завершена\n\n${report.toString(2)}" }
            } catch (error: Throwable) {
                val report = JSONObject().put("status", "failed").put("error", error.stackTraceToString())
                File(getExternalFilesDir(null), "benchmark/$label.json").writeText(report.toString(2))
                runOnUiThread { status.text = "Ошибка проверки\n${error.message}" }
            }
        }.start()
    }

    /** Entire real-photo pipeline, including crop/classifier and postprocessing. */
    private fun benchmarkPhoto(name: String): JSONObject {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val folder=PhotoFiles.folder(this).canonicalFile;val file=File(folder,name).canonicalFile
        require(file.parentFile==folder && file.isFile)
        File(getExternalFilesDir(null),"benchmark").mkdirs()
        val bitmap=PhotoFiles.decode(file)
        val reports=JSONArray()
        val measured=intent.getIntExtra("iterations",10).coerceIn(3,100)
        val series=intent.getIntExtra("series",0)
        try {
            FieldDetector(this).use {detector ->
                val modes=detector.models
                val offset=series.mod(modes.size)
                val order=modes.drop(offset)+modes.take(offset)
                for(model in order){
                    val inference=mutableListOf<Double>();val pipeline=mutableListOf<Double>()
                    var leaves=0
                    repeat(3+measured){index ->
                        val result=detector.detect(bitmap,model,"all",.25f)
                        leaves=result.boxes.size
                        if(index>=3){inference.add(result.inferenceMs);pipeline.add(result.pipelineMs)}
                    }
                    fun numbers(values: List<Double>): JSONArray = JSONArray(values)
                    fun median(values: List<Double>): Double {val sorted=values.sorted();return (sorted[(sorted.size-1)/2]+sorted[sorted.size/2])/2}
                    reports.put(JSONObject().put("model",model.id).put("seed",model.seed).put("detector_seed",model.detectorSeed)
                        .put("detector_sha256",model.sha256).put("classifier_sha256",model.classifier?.sha256)
                        .put("inference_ms",numbers(inference)).put("pipeline_ms",numbers(pipeline))
                        .put("median_inference_ms",median(inference)).put("median_pipeline_ms",median(pipeline))
                        .put("rss_bytes_after",memory("VmRSS")).put("process_hwm_bytes",memory("VmHWM")).put("detections",leaves))
                }
            }
        } finally {bitmap.recycle()}
        return JSONObject().put("status","passed").put("series",series).put("warmup",3).put("iterations",measured)
            .put("photo",name).put("photo_sha256",FieldDetector.sha256(file)).put("results",reports)
            .put("device","${Build.MANUFACTURER} ${Build.MODEL}").put("created_epoch_ms",System.currentTimeMillis())
            .put("scope","Warm real-photo complete pipeline; fresh process per series, rotated mode order; HWM is cumulative process memory")
    }

    @Suppress("UNCHECKED_CAST")
    private fun benchmark(label: String): JSONObject {
        val directory = File(getExternalFilesDir(null), "benchmark").apply { mkdirs() }
        val model = File(directory, "$label.onnx")
        require(model.isFile) { "Сначала скопировать модель через ADB: ${model.path}" }
        val environment = OrtEnvironment.getEnvironment()
        val baselineRss = memory("VmRSS")
        val iterations = intent.getIntExtra("iterations", 40)
        val warmup = 10
        val inference = mutableListOf<Double>()
        val pipeline = mutableListOf<Double>()
        var outputShape = longArrayOf()
        var detections = 0
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            environment.createSession(model.absolutePath, options).use { session ->
                val inputName = session.inputNames.first()
                val shape = (session.inputInfo[inputName]!!.info as TensorInfo).shape
                val side = shape[2].toInt()
                val inputFiles = (0..4).map { File(directory, "input_${side}_$it.bin") }
                val tensors = inputFiles.map { file ->
                    val bytes = file.readBytes()
                    OnnxTensor.createTensor(environment,
                        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                            put(bytes); rewind()
                        }.asFloatBuffer(), shape)
                }
                try {
                    repeat(warmup + iterations) { index ->
                        val start = SystemClock.elapsedRealtimeNanos()
                        session.run(mapOf(inputName to tensors[index % tensors.size])).use { result ->
                            val inferred = SystemClock.elapsedRealtimeNanos()
                            val tensor = result[0] as OnnxTensor
                            outputShape = tensor.info.shape
                            val values = (tensor.value as Array<Array<FloatArray>>)[0]
                            detections = postprocess(values, outputShape.last() == 6L)
                            val finished = SystemClock.elapsedRealtimeNanos()
                            if (index >= warmup) {
                                inference.add((inferred-start)/1e6)
                                pipeline.add((finished-start)/1e6)
                            }
                            if (index == 0) {
                                val bytes = ByteBuffer.allocate(values.sumOf { it.size }*4).order(ByteOrder.LITTLE_ENDIAN)
                                values.forEach { row -> row.forEach { bytes.putFloat(it) } }
                                File(directory, "${label}_output.bin").writeBytes(bytes.array())
                            }
                        }
                    }
                } finally {
                    tensors.forEach { it.close() }
                }
                return JSONObject().put("status", "passed").put("label", label)
                    .put("run_id", intent.getStringExtra("run_id") ?: "legacy_single_series")
                    .put("completed_epoch_ms", System.currentTimeMillis())
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                    .put("runtime", "ONNX Runtime Android 1.30.0 CPU FP32").put("threads", 4)
                    .put("input_shape", JSONArray(shape.toList())).put("output_shape", JSONArray(outputShape.toList()))
                    .put("model_bytes", model.length()).put("warmup", warmup).put("iterations", iterations)
                    .put("inference", latency(inference)).put("inference_and_postprocess", latency(pipeline))
                    .put("memory", JSONObject().put("baseline_process_rss_bytes", baselineRss)
                        .put("process_rss_bytes", memory("VmRSS")).put("peak_process_rss_bytes", memory("VmHWM")))
                    .put("last_image_detections", detections)
                    .put("scope", "Prepared tensors; excludes photo decode/resize, camera and rendering. Absolute process RAM.")
            }
        }
    }

    private data class Detection(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                                 val score: Float, val label: Int)

    private fun postprocess(output: Array<FloatArray>, nmsFree: Boolean): Int {
        if (nmsFree) return output.count { it[4] >= 0.25f }
        val candidates = mutableListOf<Detection>()
        for (index in output[0].indices) {
            var bestClass = 0
            var score = output[4][index]
            for (cls in 1 until output.size-4) {
                if (output[cls+4][index] > score) { score = output[cls+4][index]; bestClass = cls }
            }
            if (score < 0.25f) continue
            val x = output[0][index]; val y = output[1][index]
            val w = output[2][index]; val h = output[3][index]
            candidates.add(Detection(x-w/2, y-h/2, x+w/2, y+h/2, score, bestClass))
        }
        val kept = mutableListOf<Detection>()
        for (candidate in candidates.sortedByDescending { it.score }.take(30000)) {
            if (kept.none { it.label == candidate.label && iou(it, candidate) > 0.7f }) kept.add(candidate)
            if (kept.size == 300) break
        }
        return kept.size
    }

    private fun iou(a: Detection, b: Detection): Float {
        val intersection = max(0f, min(a.x2, b.x2)-max(a.x1, b.x1))*max(0f, min(a.y2, b.y2)-max(a.y1, b.y1))
        val areaA = max(0f, a.x2-a.x1)*max(0f, a.y2-a.y1)
        val areaB = max(0f, b.x2-b.x1)*max(0f, b.y2-b.y1)
        return intersection/(areaA+areaB-intersection+1e-9f)
    }

    private fun memory(field: String): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("$field:") }?.trim()?.split(Regex("\\s+"))?.get(1)?.toLong()?.times(1024) ?: 0

    private fun latency(samples: List<Double>): JSONObject {
        val sorted = samples.sorted()
        fun percentile(q: Double): Double {
            val position = q*(sorted.size-1)
            val lower = position.toInt(); val upper = min(lower+1, sorted.lastIndex)
            return sorted[lower]+(sorted[upper]-sorted[lower])*(position-lower)
        }
        return JSONObject().put("median_ms", percentile(0.5)).put("p95_ms", percentile(0.95))
    }
}
