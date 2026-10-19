package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.actions.ActionPolicy
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AppState
import com.agronomy.phytomonitor.actions.Verdict
import com.agronomy.phytomonitor.ml.CropVocabulary
import com.agronomy.phytomonitor.ml.ManifestException
import com.agronomy.phytomonitor.ml.ModelCatalog
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A-01/A-02: every model brings its own classes and crops; old and new models live in one manifest. */
class ModelCatalogTest {
    private fun find(relative: String): File {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null && !File(folder, relative).isFile) {
            folder = folder.parentFile
        }
        return File(requireNotNull(folder) { "$relative not found" }, relative)
    }

    private val taxonomy = JSONObject(find("ml-pipeline/allcrops/taxonomy_v1.json").readText())
    private val shipped = JSONObject(find("android-app/app/src/main/assets/models/manifest.json").readText())

    /** The same fields gpu_kit/train_queue.py writes to android_manifest_entry.json. */
    private fun allcropsEntry(): JSONObject {
        val classes = taxonomy.getJSONArray("classes")
        val codes = JSONArray()
        val labels = JSONArray()
        val crops = sortedSetOf<String>()
        for (index in 0 until classes.length()) {
            val item = classes.getJSONObject(index)
            codes.put(item.getString("code"))
            labels.put(item.getString("ru"))
            crops += item.getString("crop")
        }
        return JSONObject()
            .put("id", "allcrops_v1_yolo26s_416_s20261003").put("name", "YOLO allcrops_v1")
            .put("asset", "models/allcrops_v1_yolo26s_416_s20261003.onnx").put("sha256", "0".repeat(64))
            .put("family", "yolo").put("side", 416).put("seed", 20261003).put("dataset", "allcrops_v1")
            .put("classes", codes).put("labels_ru", labels).put("crops", JSONArray(crops.toList()))
            .put("crops_ru", taxonomy.getJSONObject("crops_ru"))
    }

    @Test
    fun shippedSchemaTwoModelsShareThirtyClassesWithDerivedCrops() {
        val models = ModelCatalog.parse(shipped)
        assertEquals(9, models.size)
        models.forEach { model ->
            assertEquals(30, model.classes.size)
            assertEquals(model.classes.size, model.labels.size)
        }
        val first = models.first()
        assertEquals("pepper", first.classCrops[first.classes.indexOf("Bell_pepper leaf spot")])
        assertEquals("soybean", first.classCrops[first.classes.indexOf("Soyabean leaf")])
        assertEquals("grape", first.classCrops[first.classes.indexOf("grape leaf black rot")])
        assertTrue("orange" !in first.crops)
        assertEquals("Картофель", first.cropNames["potato"])
    }

    @Test
    fun schemaThreeEntryKeepsItsOwnFortyNineClassesNextToOldModels() {
        val manifest = JSONObject(shipped.toString()).put("schema", 3)
        manifest.getJSONArray("models").put(allcropsEntry())
        val models = ModelCatalog.parse(manifest)
        val allcrops = models.last()
        assertEquals(49, allcrops.classes.size)
        assertEquals(15, allcrops.crops.size)
        assertEquals("Тыква/кабачок", allcrops.cropNames["squash"])
        assertEquals(30, models.first().classes.size)
        val strawberry = allcrops.classCrops.indices.filter { allcrops.classCrops[it] == "strawberry" }
        assertEquals(listOf("strawberry__healthy", "strawberry__leaf_scorch"), strawberry.map { allcrops.classes[it] })
        assertEquals("Земляника: ожог (пятнистость) листьев", allcrops.labels[strawberry.last()])
    }

    @Test
    fun everyTaxonomyCropHasNamesAndSpokenForms() {
        val crops = taxonomy.getJSONObject("crops_ru")
        crops.keys().forEach { crop ->
            assertEquals(crop, crops.getString(crop), CropVocabulary.names[crop])
            assertTrue(crop, CropVocabulary.spoken[crop].orEmpty().isNotEmpty())
            assertTrue(crop, CropVocabulary.chooseForms[crop].orEmpty().isNotEmpty())
        }
    }

    @Test
    fun strawberryCanBeChosenBySeveralNamesWhenTheModelKnowsIt() {
        assertEquals("strawberry", VoiceCommands.parse("выбери клубнику")!!.value)
        assertEquals("strawberry", VoiceCommands.parse("земляника")!!.value)
        assertEquals("potato", VoiceCommands.parse("выбери картошку")!!.value)
        assertEquals("tomato", VoiceCommands.parse("помидоры")!!.value)
        val request = ActionRequest(VoiceAction.CROP, "strawberry", ActionSource.VOICE)
        val allcrops = ModelCatalog.parse(JSONObject().put("schema", 3).put("models", JSONArray().put(allcropsEntry()))).single()
        assertTrue(ActionPolicy.resolve(request, AppState(crops = allcrops.crops)) is Verdict.Run)
        val legacy = ModelCatalog.parse(shipped).first()
        assertTrue(ActionPolicy.resolve(request, AppState(crops = legacy.crops)) is Verdict.Run)
        val orange = request.copy(value = "orange")
        assertTrue(ActionPolicy.resolve(orange, AppState(crops = legacy.crops)) is Verdict.Reject)
    }

    @Test
    fun inconsistentManifestsAreRefused() {
        val shortLabels = allcropsEntry().put("labels_ru", JSONArray().put("один"))
        val missingCrop = allcropsEntry().put("crops", JSONArray().put("tomato"))
        listOf(
            JSONObject().put("schema", 3).put("models", JSONArray().put(shortLabels)),
            JSONObject().put("schema", 3).put("models", JSONArray().put(missingCrop)),
            JSONObject().put("schema", 4).put("models", JSONArray())
        ).forEach { manifest ->
            try {
                ModelCatalog.parse(manifest)
                fail("accepted $manifest")
            } catch (expected: ManifestException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }
}
