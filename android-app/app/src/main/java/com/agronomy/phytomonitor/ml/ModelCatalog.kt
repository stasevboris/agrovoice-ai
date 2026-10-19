package com.agronomy.phytomonitor.ml

import org.json.JSONArray
import org.json.JSONObject

/**
 * Crop names for the screen and for speech. Display names follow `crops_ru` of taxonomy_v1; the spoken
 * forms are ordinary Russian words for the same crops, kept in the app because they are language, not data.
 */
object CropVocabulary {
    val names = linkedMapOf(
        "apple" to "Яблоня", "blueberry" to "Голубика", "cherry" to "Вишня", "corn" to "Кукуруза",
        "cucumber" to "Огурец", "grape" to "Виноград", "orange" to "Апельсин", "peach" to "Персик",
        "pepper" to "Перец", "potato" to "Картофель", "raspberry" to "Малина", "soybean" to "Соя",
        "squash" to "Тыква/кабачок", "strawberry" to "Земляника", "tomato" to "Томат"
    )

    /** Phrases a person says to choose the crop; «выбери …» forms are added by VoiceCommands. */
    val spoken = mapOf(
        "apple" to listOf("яблоня", "яблони", "яблоки"),
        "blueberry" to listOf("голубика"),
        "cherry" to listOf("вишня", "вишни"),
        "corn" to listOf("кукуруза"),
        "cucumber" to listOf("огурец", "огурцы"),
        "grape" to listOf("виноград"),
        "orange" to listOf("апельсин", "апельсины"),
        "peach" to listOf("персик", "персики"),
        "pepper" to listOf("перец"),
        "potato" to listOf("картофель", "картошка"),
        "raspberry" to listOf("малина"),
        "soybean" to listOf("соя"),
        "squash" to listOf("тыква", "кабачок", "кабачки"),
        "strawberry" to listOf("земляника", "клубника"),
        "tomato" to listOf("томат", "томаты", "помидоры")
    )

    /** What follows «выбери …»: the accusative where it differs from the name. */
    val chooseForms = mapOf(
        "apple" to listOf("яблоню", "яблоки"),
        "blueberry" to listOf("голубику"),
        "cherry" to listOf("вишню"),
        "corn" to listOf("кукурузу"),
        "cucumber" to listOf("огурец", "огурцы"),
        "grape" to listOf("виноград"),
        "orange" to listOf("апельсин", "апельсины"),
        "peach" to listOf("персик", "персики"),
        "pepper" to listOf("перец"),
        "potato" to listOf("картофель", "картошку"),
        "raspberry" to listOf("малину"),
        "soybean" to listOf("сою"),
        "squash" to listOf("тыкву", "кабачок", "кабачки"),
        "strawberry" to listOf("землянику", "клубнику"),
        "tomato" to listOf("томат", "томаты", "помидоры")
    )

    /** Old 30-class models name classes in English; the first word gives the crop. */
    private val legacyFirstWords = mapOf("bell_pepper" to "pepper", "soyabean" to "soybean")

    fun cropOfLegacyClass(name: String): String {
        val first = name.trim().substringBefore(' ').lowercase()
        return legacyFirstWords[first] ?: first
    }

    /** taxonomy_v1 codes are «crop__condition». */
    fun cropOfCode(code: String): String = code.substringBefore("__")
}

class ManifestException(message: String) : Exception(message)

/**
 * Reads models/manifest.json. Schema 2 has one class list for all models; schema 3 lets each model carry
 * its own `classes`, `labels_ru`, `crops` and `crops_ru` (the entry written by gpu_kit/train_queue.py),
 * falling back to the shared list for older models. Pure Kotlin + org.json, tested on the JVM.
 */
object ModelCatalog {
    fun parse(manifest: JSONObject): List<DetectorModel> {
        val schema = manifest.optInt("schema", 2)
        if (schema !in 2..3) {
            throw ManifestException("Неизвестная схема манифеста моделей: $schema")
        }
        val sharedClasses = manifest.optJSONArray("classes")?.let(::strings).orEmpty()
        val sharedLabels = manifest.optJSONArray("labels_ru")?.let(::strings).orEmpty()
        val items = manifest.getJSONArray("models")
        val sharedTaxonomy = taxonomyVersion(manifest)
        return List(items.length()) { index -> model(items.getJSONObject(index), sharedClasses, sharedLabels, sharedTaxonomy) }
    }

    private fun model(data: JSONObject, sharedClasses: List<String>, sharedLabels: List<String>, sharedTaxonomy: String?): DetectorModel {
        val id = data.getString("id")
        val own = data.optJSONArray("classes")
        val classes = own?.let(::strings) ?: sharedClasses
        val labels = data.optJSONArray("labels_ru")?.let(::strings) ?: sharedLabels
        if (classes.isEmpty() || labels.size != classes.size) {
            throw ManifestException("$id: число классов (${classes.size}) и подписей (${labels.size}) не совпадает")
        }
        val classCrops = classes.map { if (own != null) CropVocabulary.cropOfCode(it) else CropVocabulary.cropOfLegacyClass(it) }
        val declared = data.optJSONArray("crops")?.let(::strings)
        val crops = declared ?: classCrops.distinct().sorted()
        val unknown = classCrops.toSet() - crops.toSet()
        if (unknown.isNotEmpty()) {
            throw ManifestException("$id: культуры классов ${unknown.sorted()} не перечислены в crops")
        }
        val cropNames = crops.associateWith { crop ->
            data.optJSONObject("crops_ru")?.optString(crop)?.ifBlank { null } ?: CropVocabulary.names[crop] ?: crop
        }
        val classifier = data.optJSONObject("classifier")?.let {
            ClassifierGraph(it.getString("id"), it.getString("asset"), it.getString("sha256"), it.getInt("seed"), it.getString("kind"), it.optInt("side", 224))
        }
        val seed = data.optInt("seed", 20261003)
        return DetectorModel(
            id = id,
            name = data.getString("name"),
            asset = data.getString("asset"),
            sha256 = data.getString("sha256"),
            family = data.optString("family", "yolo"),
            side = data.optInt("side", 416),
            seed = seed,
            detectorSeed = data.optInt("detector_seed", seed),
            dataset = data.optString("dataset", "clean v2"),
            provisional = data.optBoolean("provisional", true),
            classifier = classifier,
            classes = classes,
            labels = labels,
            classCrops = classCrops,
            crops = crops,
            cropNames = cropNames,
            taxonomyVersion = taxonomyVersion(data) ?: sharedTaxonomy.takeIf { own == null }
        )
    }

    private fun taxonomyVersion(data: JSONObject): String? = data.optString("taxonomy_version")
        .takeIf { it.isNotBlank() && it != "null" }

    private fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }
}
