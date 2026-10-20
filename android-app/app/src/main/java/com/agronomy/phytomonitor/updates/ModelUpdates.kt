package com.agronomy.phytomonitor.updates

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.agronomy.phytomonitor.ml.DetectorModel
import com.agronomy.phytomonitor.ml.ModelCatalog
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One process snapshot. Installing changes only the next process's selection. Call on an I/O worker. */
class ModelUpdates(
    val store: VerifiedUpdateStore,
    private val bundledManifest: JSONObject,
    private val taxonomy: JSONObject,
    private val validate: (File, DetectorModel) -> Unit = ::validateGraph
) {
    data class Loaded(val manifest: JSONObject, val model: DetectorModel?, val release: UpdateRelease?, val notice: String? = null)

    private val bundled = ModelCatalog.parse(bundledManifest)
    private val canonical = taxonomy.getJSONArray("classes")
    private val startup = store.candidates("model")
    private val rejected = mutableSetOf<String>()
    private var loaded: Loaded? = null
    private val mutableState = MutableStateFlow<Loaded?>(null)
    val state = mutableState.asStateFlow()
    @Volatile var pending: UpdateRelease? = null
        private set

    fun compatible(release: UpdateRelease): DetectorModel {
        val codes = List(canonical.length()) { canonical.getJSONObject(it).getString("code") }
        val model = UpdateCompatibility.model(release, bundled, codes)
        if (model.classes == codes) {
            val labels = List(canonical.length()) { canonical.getJSONObject(it).getString("ru") }
            val crops = List(canonical.length()) { canonical.getJSONObject(it).getString("crop") }
            require(model.labels == labels && model.classCrops == crops && model.crops.toSet() == crops.toSet()) {
                "Подписи или культуры модели не соответствуют taxonomy_v1"
            }
            val names = taxonomy.getJSONObject("crops_ru")
            require(model.cropNames.all { (crop, name) -> name == names.getString(crop) }) {
                "Названия культур модели не соответствуют taxonomy_v1"
            }
        } else {
            val previous = bundled.single { it.id == model.id }
            require(model.side == previous.side && model.classCrops == previous.classCrops &&
                model.cropNames == previous.cropNames) { "Предобработка или культуры встроенной модели изменены" }
        }
        return model
    }

    @Synchronized fun load(): Loaded {
        loaded?.let { return it }
        var failed = rejected.isNotEmpty()
        for (release in startup) {
            if (release.sha256 in rejected) continue
            val model = try {
                require(store.valid(release)) { "Файл модели повреждён" }
                compatible(release).also { validate(store.file(release), it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
                continue
            }
            val manifest = JSONObject(bundledManifest.toString())
            val original = release.metadata.getJSONObject("manifest_entry")
            val entry = JSONObject(original.toString()).put("asset", model.asset)
                .put("update_release", release.toJson())
            model.taxonomyVersion?.let { entry.put("taxonomy_version", it) }
            val entries = manifest.getJSONArray("models")
            val merged = JSONArray()
            for (index in 0 until entries.length()) {
                if (entries.getJSONObject(index).getString("id") != model.id) merged.put(entries.getJSONObject(index))
            }
            manifest.put("models", merged.put(entry))
            val notice = if (failed) "Обновлённая модель не прошла проверку. Доступна прежняя версия ${release.version}. Проверьте обновления." else null
            return remember(Loaded(manifest, model, release, notice))
        }
        val notice = if (failed) "Обновлённые модели не прошли проверку. Доступна встроенная модель. Проверьте обновления." else null
        return remember(Loaded(JSONObject(bundledManifest.toString()), null, null, notice))
    }

    private fun remember(selection: Loaded): Loaded = selection.also {
        loaded = it
        mutableState.value = it
    }

    /** Runtime failure uses the same startup candidates, never a model installed during this process. */
    @Synchronized fun reject(sha256: String): Loaded {
        rejected += sha256
        loaded = null
        return load()
    }

    fun offered(releases: List<UpdateRelease>): UpdateRelease? {
        val installed = store.available("model").firstOrNull()?.sha256
        return releases.lastOrNull { it.kind == "model" && runCatching { compatible(it) }.isSuccess }
            ?.takeIf { it.sha256 != installed }
    }

    fun install(release: UpdateRelease, active: () -> Boolean = { true },
                commit: (() -> Unit) -> Unit = { it() }) {
        val previous = load().release?.takeIf { store.valid(it) }
        val model = compatible(release)
        require(store.valid(release)) { "Размер или SHA модели не совпадает" }
        validate(store.file(release), model)
        if (!active() || Thread.currentThread().isInterrupted) throw CancellationException("Установка модели отменена")
        commit { store.activate(release, previous) }
        pending = release.takeIf { it.sha256 != load().release?.sha256 }
    }

    companion object {
        @Volatile private var process: ModelUpdates? = null

        fun get(context: Context): ModelUpdates = process ?: synchronized(this) {
            process ?: ModelUpdates(KnowledgeUpdates.store(context),
                JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() }),
                JSONObject(context.assets.open("taxonomies/taxonomy_v1.json").bufferedReader().use { it.readText() }))
                .also { process = it }
        }

        fun supportedGraph(input: LongArray, output: LongArray, inputFloat: Boolean, outputFloat: Boolean,
                           side: Int, classes: Int): Boolean = inputFloat && outputFloat &&
            input.contentEquals(longArrayOf(1, 3, side.toLong(), side.toLong())) &&
            UpdateCompatibility.supportedOutput(output, classes)

        /** Load and execute before committing. The shared global environment is never closed. */
        fun validateGraph(file: File, model: DetectorModel) {
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                environment.createSession(file.absolutePath, options).use { session ->
                    require(session.inputNames.size == 1 && session.outputNames.size == 1) { "Неверное число входов/выходов модели" }
                    val input = session.inputInfo.values.single().info as? TensorInfo
                    val output = session.outputInfo.values.single().info as? TensorInfo
                    require(input != null && output != null && supportedGraph(input.shape, output.shape,
                        input.type == OnnxJavaType.FLOAT, output.type == OnnxJavaType.FLOAT, model.side, model.classes.size)) {
                        "Неподдерживаемый тип или форма ONNX"
                    }
                    OnnxTensor.createTensor(environment, FloatBuffer.allocate(3 * model.side * model.side), input.shape).use { tensor ->
                        session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                            val actual = result[0] as? OnnxTensor ?: error("ONNX не вернул тензор")
                            require(actual.info.type == OnnxJavaType.FLOAT && actual.info.shape.contentEquals(output.shape)) {
                                "Фактический выход ONNX не совпадает с объявленным"
                            }
                            val values = actual.floatBuffer
                            while (values.hasRemaining()) require(values.get().isFinite()) { "ONNX вернул неконечные значения" }
                        }
                    }
                }
            }
        }
    }
}
