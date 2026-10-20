package com.agronomy.phytomonitor.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.journal.*
import com.agronomy.phytomonitor.ml.ModelCatalog
import com.agronomy.phytomonitor.ml.ResultProvenanceSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSeriesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val account = "http://127.0.0.1:8028|fixture"
    private val databases = mutableListOf<JournalDatabase>()

    private fun context(name: String): Context {
        val root = temporary.newFolder(name)
        return object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?) = File(root, "files").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }

    private fun db() = Room.inMemoryDatabaseBuilder(base, JournalDatabase::class.java)
        .allowMainThreadQueries().build().also { databases += it }

    private data class Fixture(val remote: JSONObject, val bytes: Map<String, ByteArray>)

    private fun fixture(count: Int = 2, detections: Int = 1, feedback: Boolean = false): Fixture {
        val context = context("source")
        val journal = InspectionJournal(context, db())
        val manifest = JSONObject(base.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
        val model = ModelCatalog.parse(manifest).first { it.family == "yolo" }
        val models = manifest.getJSONArray("models")
        val metadata = (0 until models.length()).map { models.getJSONObject(it) }.first { it.getString("id") == model.id }
        val frames = (0 until count).map { index ->
            val photo = File(context.filesDir, "photos/fixture-$index.jpg").apply {
                parentFile!!.mkdirs()
                writeText("SYNTHETIC frame $index")
            }
            val boxes = JSONArray((0 until detections).map { number ->
                val cls = number % model.classes.size
                JSONObject().put("class_id", cls).put("class_name", model.classes[cls]).put("label_ru", model.labels[cls])
                    .put("confidence", 0.75).put("xyxy_pixels", JSONArray(listOf(10.0, 20.0, 100.0, 140.0)))
            })
            val report = File(context.filesDir, "results/fixture-$index.json").apply {
                parentFile!!.mkdirs()
                writeText(JSONObject().put("photo", photo.path).put("photo_sha256", InspectionJournal.sha256(photo))
                    .put("model", model.id).put("model_sha256", model.sha256).put("model_metadata", metadata)
                    .put("decoded_width", 2048).put("decoded_height", 1536).put("detections", boxes)
                    .put("result_provenance", ResultProvenanceSnapshot.create(model, 2048, 1536, "all", 0.25f, metadata))
                    .put("scope", "SYNTHETIC TEST: no model inference performed").toString(2))
            }
            InspectionFrame("frame-$index", report, if (index == 0) FrameRole.FRONT else FrameRole.BACK)
        }
        val saved = journal.saveSeries("series", frames, "Серия", "uncertain", "Участок", plant = "Растение")
        if (feedback) journal.saveFrameFeedback("frame-1", 0, "incorrect", "Другая культура")
        val snapshot = SyncPayload.snapshot(account, journal.details(saved.id)!!, 0, saved.created, { true })
        val files = JSONArray(snapshot.photoFiles)
        val bytes = (0 until files.length()).map { files.getJSONObject(it) }
            .associate { it.getString("id") to File(it.getString("path")).readBytes() }
        val remote = JSONObject(snapshot.request).put("version", 1).put("revision", 1).put("expert_status", "unreviewed")
        return Fixture(remote, bytes)
    }

    private fun api(fixture: Fixture, corrupt: String? = null) = object : SyncApi {
        override fun push(update: JSONObject): JSONObject = throw AssertionError("not used")
        override fun upload(id: String, file: File, sha: String) = throw AssertionError("not used")
        override fun pull(after: Long) = throw AssertionError("not used")
        override fun download(id: String, target: File, bytes: Long) {
            target.writeBytes(if (id == corrupt) byteArrayOf(99) else fixture.bytes.getValue(id))
        }
    }

    @After fun close() = databases.forEach { it.close() }

    @Test fun rejectsFeedbackClaimingAnotherKnownAnalysisVersionBeforeDownloading() {
        val fixture = fixture(feedback = true)
        fixture.remote.getJSONObject("data").getJSONArray("frame_feedback").getJSONObject(0).put("analysis_version", 2)
        val target = context("feedback-version-target")
        val database = db()
        val photos = File(target.filesDir, "photos")
        val importer = SyncImporter(database, photos, File(target.cacheDir, "stage"))
        assertTrue(runCatching { importer.importRecord(account, fixture.remote, api(fixture)) }.isFailure)
        assertEquals(0, database.journal().inspectionCount())
        assertTrue(photos.listFiles().orEmpty().isEmpty())
    }

    @Test fun feedbackSurvivesSyncAndArchiveWithoutRewritingPrediction() {
        val fixture = fixture(feedback = true)
        val data = fixture.remote.getJSONObject("data")
        assertEquals(1, data.getJSONArray("frame_feedback").length())
        assertFalse(data.getJSONObject("prediction").has("frame_feedback"))
        val target = context("feedback-target")
        val database = db()
        val importer = SyncImporter(database, File(target.filesDir, "photos"), File(target.cacheDir, "stage"))
        assertTrue(importer.importRecord(account, fixture.remote, api(fixture)))
        val journal = InspectionJournal(target, database)
        val details = journal.details(journal.list().single().id)!!
        assertNull(details.photos[0].feedback)
        assertEquals("Другая культура", details.photos[1].feedback!!.correction)
        assertEquals("user", details.photos[1].feedback!!.source)
        val archive = ByteArrayOutputStream().also { journal.exportArchive(it) }.toByteArray()
        val restored = InspectionJournal(context("feedback-restored"), db())
        assertEquals(1, restored.import(ByteArrayInputStream(archive)).added)
        val after = restored.details(details.inspection.id)!!
        assertEquals(details.photos.map { it.feedback }, after.photos.map { it.feedback })
        assertEquals(details.photos.map { it.photo.predictionJson }, after.photos.map { it.photo.predictionJson })
        val binding = requireNotNull(SyncArchiveBinding.recover(account, after))
        val snapshot = SyncPayload.snapshot(account, after, 0, binding.snapshotUpdatedAt, { true }, binding)
        assertTrue(SyncPayload.sameJson(data, JSONObject(snapshot.request).getJSONObject("data")))
    }

    @Test fun everyFrameHasExplicitWireMetadataWithoutLocalPaths() {
        val fixture = fixture()
        val prediction = fixture.remote.getJSONObject("data").getJSONObject("prediction")
        val frames = prediction.getJSONArray("agrovoice_frames")
        assertEquals(2, frames.length())
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            assertEquals(index, frame.getInt("order"))
            assertEquals(if (index == 0) "front" else "back", frame.getString("role"))
            assertFalse(frame.getJSONObject("prediction").has("photo"))
            assertTrue(frame.getJSONObject("prediction").has("result_provenance"))
        }
    }

    @Test fun pullArchiveRestoreAndRepeatKeepAllIdentitiesReportsAndServerBinding() {
        val fixture = fixture()
        val target = context("target")
        val database = db()
        val importer = SyncImporter(database, File(target.filesDir, "photos"), File(target.cacheDir, "stage"))
        assertTrue(importer.importRecord(account, fixture.remote, api(fixture)))
        assertFalse(importer.importRecord(account, fixture.remote, api(fixture)))
        val journal = InspectionJournal(target, database)
        val details = journal.details(journal.list().single().id)!!
        assertEquals(2, details.photos.size)
        assertTrue(details.photos.all { it.photo.predictionJson != null && it.detections.size == 1 })
        assertTrue(details.photos.all { it.plantName == "Растение" })
        val archive = ByteArrayOutputStream().also { journal.exportArchive(it) }.toByteArray()
        val restoredContext = context("restored")
        val restoredDb = db()
        val restored = InspectionJournal(restoredContext, restoredDb)
        assertEquals(1, restored.import(ByteArrayInputStream(archive)).added)
        assertEquals(1, restored.import(ByteArrayInputStream(archive)).unchanged)
        val roundtrip = restored.details(details.inspection.id)!!
        assertEquals(details.photos.map { it.photo.id }, roundtrip.photos.map { it.photo.id })
        assertEquals(details.photos.map { it.photo.predictionJson }, roundtrip.photos.map { it.photo.predictionJson })
        val binding = requireNotNull(SyncArchiveBinding.recover(account, roundtrip))
        val snapshot = SyncPayload.snapshot(account, roundtrip, 0, binding.snapshotUpdatedAt, { true }, binding)
        assertEquals(fixture.remote.getString("id"), snapshot.serverId)
        assertTrue(SyncPayload.sameJson(fixture.remote.getJSONObject("data"), JSONObject(snapshot.request).getJSONObject("data")))
        assertEquals(2, JSONArray(snapshot.photoFiles).length())
    }

    @Test fun conflictingPrimaryProvenanceIsRejectedBeforeImportAndCorrectedRetryRestoresArchive() {
        val fixture = fixture()
        val original = fixture.remote.toString()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { data -> data.getJSONObject("prediction").getJSONArray("agrovoice_frames")
                .getJSONObject(0).getJSONObject("prediction").put("model", "unrelated-model") },
            { data -> data.put("model_sha256", "b".repeat(64)) },
            { data -> data.getJSONObject("prediction").put("model", "unrelated-model") }
        )
        mutations.forEachIndexed { index, mutate ->
            val remote = JSONObject(original)
            mutate(remote.getJSONObject("data"))
            val target = context("target-$index")
            val database = db()
            val importer = SyncImporter(database, File(target.filesDir, "photos"), File(target.cacheDir, "stage"))
            assertTrue("Conflicting source evidence must be rejected ($index)",
                runCatching { importer.importRecord(account, remote, api(fixture)) }.isFailure)
            assertEquals(0, database.journal().inspectionCount())
            assertTrue(database.sync().records(account).isEmpty())

            assertTrue(importer.importRecord(account, JSONObject(original), api(fixture)))
            val journal = InspectionJournal(target, database)
            val archive = ByteArrayOutputStream().also { journal.exportArchive(it) }.toByteArray()
            val restored = InspectionJournal(context("restored-$index"), db())
            val report = restored.import(ByteArrayInputStream(archive))
            assertEquals(1, report.added)
            assertTrue(report.issues.isEmpty())
            val before = journal.details(journal.list().single().id)!!
            val after = restored.details(before.inspection.id)!!
            assertTrue(JournalRecords.sameJson(JSONObject(before.inspection.predictionJson),
                JSONObject(after.inspection.predictionJson)))
            assertEquals(before.photos.map { it.photo.predictionJson }, after.photos.map { it.photo.predictionJson })
        }
    }

    @Test fun framePhotoHashCannotDisagreeWithDownloadedReference() {
        val fixture = fixture()
        fixture.remote.getJSONObject("data").getJSONObject("prediction").getJSONArray("agrovoice_frames")
            .getJSONObject(1).getJSONObject("prediction").put("photo_sha256", "b".repeat(64))
        val target = context("target")
        val database = db()
        val importer = SyncImporter(database, File(target.filesDir, "photos"), File(target.cacheDir, "stage"))

        assertTrue(runCatching { importer.importRecord(account, fixture.remote, api(fixture)) }.isFailure)
        assertEquals(0, database.journal().inspectionCount())
        assertTrue(database.sync().records(account).isEmpty())
    }

    @Test fun legacyRemoteSeriesDoesNotCopyFirstAnalysisOntoUnknownExtraFrames() {
        val fixture = fixture()
        fixture.remote.getJSONObject("data").getJSONObject("prediction").remove("agrovoice_frames")
        val target = context("target")
        val database = db()
        assertTrue(SyncImporter(database, File(target.filesDir, "photos"), File(target.cacheDir, "stage"))
            .importRecord(account, fixture.remote, api(fixture)))
        val frames = database.journal().inspections().single().orderedPhotos()
        assertEquals(2, frames.size)
        assertEquals(1, frames.first().detections.size)
        assertTrue(frames.last().detections.isEmpty())
        assertNull(frames.last().photo.predictionJson)
        assertEquals("unspecified", frames.last().photo.frameRole)
    }

    @Test fun failedSecondFrameDownloadLeavesNoPartialRowsAndFullRetryRecovers() {
        val fixture = fixture()
        val second = fixture.remote.getJSONObject("data").getJSONArray("photos").getJSONObject(1).getString("id")
        val target = context("target")
        val database = db()
        val importer = SyncImporter(database, File(target.filesDir, "photos"), File(target.cacheDir, "stage"))
        assertTrue(runCatching { importer.importRecord(account, fixture.remote, api(fixture, second)) }.isFailure)
        assertEquals(0, database.journal().inspectionCount())
        assertTrue(database.sync().records(account).isEmpty())
        assertTrue(importer.importRecord(account, fixture.remote, api(fixture)))
        assertEquals(2, database.journal().inspections().single().photos.size)
    }

    @Test fun tenFramePayloadBudgetIsRecordedWithRealManifestMetadataAndSyntheticBoxes() {
        val fixture = fixture(count = 10, detections = 32)
        val prediction = fixture.remote.getJSONObject("data").getJSONObject("prediction")
        assertEquals(10, prediction.getJSONArray("agrovoice_frames").length())
        val directory = File("../../artifacts/verification/autonomous_20261019").apply { mkdirs() }
        File(directory, "series-wire-sample.json").writeText(fixture.remote.toString())
    }

    @Test fun tenFramesAtYoloDetectionCapRetainAllBoxesForBudgetMeasurement() {
        val fixture = fixture(count = 10, detections = 300)
        val prediction = fixture.remote.getJSONObject("data").getJSONObject("prediction")
        val frames = prediction.getJSONArray("agrovoice_frames")
        assertEquals(10, frames.length())
        for (index in 0 until frames.length()) {
            assertEquals(300, frames.getJSONObject(index).getJSONObject("prediction").getJSONArray("detections").length())
        }
        val directory = File("../../artifacts/verification/autonomous_20261019").apply { mkdirs() }
        File(directory, "series-wire-max-sample.json").writeText(fixture.remote.toString())
    }
}
