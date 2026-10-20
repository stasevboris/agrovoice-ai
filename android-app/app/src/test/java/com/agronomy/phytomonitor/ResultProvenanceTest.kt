package com.agronomy.phytomonitor

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.ml.DetectionResult
import com.agronomy.phytomonitor.ml.DetectorModel
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ml.ModelCatalog
import com.agronomy.phytomonitor.ml.PhotoFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultProvenanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var bitmap: Bitmap
    private lateinit var photo: File
    private lateinit var manifest: JSONObject
    private lateinit var models: List<DetectorModel>

    @Before
    fun prepareSyntheticPhoto() {
        // Historical reports from lab remain readable in field, even when their weights
        // are not packaged. Exercise serialization for all catalog families in both flavors.
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "android-app/app/src/main/assets/models/manifest.json").isFile) {
            root = requireNotNull(root.parentFile) { "Source model catalog not found" }
        }
        manifest = JSONObject(File(root, "android-app/app/src/main/assets/models/manifest.json").readText())
        models = ModelCatalog.parse(manifest)
        bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        photo = PhotoFiles.newCapture(context)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After
    fun releaseBitmap() {
        bitmap.recycle()
    }

    private fun save(model: DetectorModel, crop: String = "all", threshold: Float = .35f): File {
        val result = DetectionResult(model, listOf(LeafDetection(0, .8f, 1f, 2f, 15f, 18f)), 0.0, 0.0)
        return PhotoFiles.saveResult(context, photo, bitmap, result, manifest, crop, threshold)
    }

    @Test
    fun savedResultHasOrderedClassSnapshotWithoutInventingLegacyTaxonomy() {
        val model = models.first { it.family == "yolo" }
        val report = JSONObject(save(model).readText())
        assertTrue("New result must contain reproducible provenance", report.has("result_provenance"))
        val provenance = report.getJSONObject("result_provenance")
        assertEquals("unspecified", provenance.getString("taxonomy_version"))
        assertEquals(model.id, provenance.getString("model_id"))
        assertEquals(model.sha256, provenance.getString("model_sha256"))
        assertEquals(JSONArray(model.classes).toString(), provenance.getJSONArray("classes").toString())
        assertEquals(JSONArray(model.labels).toString(), provenance.getJSONArray("labels_ru").toString())
        val expected = MessageDigest.getInstance("SHA-256").digest(JSONArray(model.classes).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, provenance.getString("class_order_sha256"))
        assertNotEquals("taxonomy_v1", provenance.getString("taxonomy_version"))
    }

    @Test
    fun explicitlyDeclaredTaxonomyIsSavedWithoutInferringFromDataset() {
        val entry = manifest.getJSONArray("models").getJSONObject(0)
        entry.put("taxonomy_version", "fixture-taxonomy-v7")
        val model = ModelCatalog.parse(manifest).first()
        assertEquals("fixture-taxonomy-v7", JSONObject(save(model).readText())
            .getJSONObject("result_provenance").getString("taxonomy_version"))
    }

    @Test
    fun repeatedAnalysisAndClassReorderingKeepOriginalReportUnchanged() {
        val model = models.first { it.family == "yolo" }
        val first = save(model)
        val original = first.readBytes()
        val changed = model.copy(classes = model.classes.reversed(), labels = model.labels.reversed(),
            classCrops = model.classCrops.reversed())
        val second = save(changed, threshold = .7f)
        assertNotEquals(first, second)
        assertTrue(original.contentEquals(first.readBytes()))
        val firstJson = JSONObject(first.readText())
        val secondJson = JSONObject(second.readText())
        assertNotEquals(firstJson.getJSONObject("result_provenance").getString("class_order_sha256"),
            secondJson.getJSONObject("result_provenance").getString("class_order_sha256"))
        assertEquals(model.classes.first(), firstJson.getJSONArray("detections").getJSONObject(0).getString("class_name"))
        assertEquals(changed.classes.first(), secondJson.getJSONArray("detections").getJSONObject(0).getString("class_name"))
    }

    @Test
    fun yoloAndTwoStageAndDetrRecordTheirActualDifferentDecodingPolicies() {
        val yolo = JSONObject(save(models.first { it.family == "yolo" }).readText()).getJSONObject("result_provenance")
        val preprocess = yolo.getJSONObject("preprocessing")
        assertEquals("RGB", preprocess.getString("channels"))
        assertEquals("NCHW", preprocess.getString("layout"))
        assertEquals(114, preprocess.getInt("padding_value"))
        assertEquals(208, preprocess.getJSONObject("letterbox").getInt("resized_height"))
        assertEquals(104, preprocess.getJSONObject("letterbox").getInt("pad_y"))
        assertEquals(0.7, yolo.getJSONObject("postprocessing").getJSONObject("nms").getDouble("iou_threshold"), 1e-6)
        assertEquals(false, yolo.getJSONObject("postprocessing").getJSONObject("nms").getBoolean("class_agnostic"))

        val twoStageModel = models.first { it.classifier != null }
        val twoStage = JSONObject(save(twoStageModel).readText()).getJSONObject("result_provenance")
        assertEquals(0.1, twoStage.getJSONObject("postprocessing").getDouble("proposal_threshold"), 1e-6)
        assertTrue(twoStage.getJSONObject("postprocessing").getJSONObject("nms").getBoolean("class_agnostic"))
        val classifier = twoStage.getJSONObject("classifier")
        assertEquals(twoStageModel.classifier!!.sha256, classifier.getString("sha256"))
        assertEquals(32, classifier.getInt("max_classified_leaves"))
        assertEquals(0.1, classifier.getDouble("margin_each_side"), 1e-6)
        assertEquals(twoStageModel.classifier!!.side, classifier.getInt("input_side"))

        val detr = JSONObject(save(models.first { it.family == "detr" }).readText()).getJSONObject("result_provenance")
        assertEquals(false, detr.getJSONObject("postprocessing").getJSONObject("nms").getBoolean("enabled"))
        assertEquals("xyxy_score_class", detr.getJSONObject("postprocessing").getString("decoder"))
    }

    @Test
    fun oldReportShowsMissingTaxonomyAndDoesNotAcquireCurrentClasses() {
        val old = JSONObject().put("model", "historical-model").put("model_sha256", "old-sha").toString()
        val info = savedProvenanceInfo(old)
        assertTrue(info.taxonomy.contains("не указана"))
        assertTrue(info.details.contains("Модель: historical-model"))
        assertTrue(info.details.contains("SHA-256 модели: old-sha"))
        assertTrue(info.details.any { it.contains("не сохранены") })
        assertTrue(savedProvenanceInfo("invalid old JSON").taxonomy.contains("не указана"))
    }

    @Test
    fun savedViewStillUsesOriginalModelAfterAnotherModelIsAnalysed() {
        val originalModel = models.first { it.family == "yolo" }
        val original = save(originalModel)
        val originalText = original.readText()
        val info = savedProvenanceInfo(originalText)
        val another = save(models.first { it.family == "detr" })
        assertEquals(info, savedProvenanceInfo(original.readText()))
        assertTrue(info.details.contains("Модель: ${originalModel.id}"))
        assertNotEquals(info.details.first(), savedProvenanceInfo(another.readText()).details.first())
        assertEquals(originalText, original.readText())
    }

    @Test
    fun ownClassListDoesNotInheritUnrelatedSharedTaxonomy() {
        manifest.put("taxonomy_version", "shared-fixture-v1")
        val entry = manifest.getJSONArray("models").getJSONObject(0)
        entry.put("classes", JSONArray().put("apple__healthy"))
        entry.put("labels_ru", JSONArray().put("Fixture"))
        val parsed = ModelCatalog.parse(manifest)
        assertEquals(null, parsed.first().taxonomyVersion)
        assertEquals("shared-fixture-v1", parsed[1].taxonomyVersion)
    }
}
