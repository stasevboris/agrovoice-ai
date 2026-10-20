package com.agronomy.phytomonitor.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.journal.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncImporterTest {
    private lateinit var db: JournalDatabase
    private lateinit var folder: File
    private lateinit var source: File
    private lateinit var remote: JSONObject
    private val account = "http://127.0.0.1:8028|fixture"

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        folder = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        source = File(folder, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = JournalFixtures.legacyRecord("old-remote", source.path, InspectionJournal.sha256(source))
        original.getJSONObject("prediction").put("model_sha256", "a".repeat(64))
        val parsed = JournalRecords.parse(original)
        val record = JournalRecords.toRecord(parsed, "plot-original", "plant-original", source.path)
        val details = InspectionWithDetails(record.inspection,
            PlotEntity("plot-original", "Поле", updatedAt = 1), listOf(PhotoWithDetections(record.photo, record.detections, "Куст")))
        val snapshot = SyncPayload.snapshot(account, details, 0, record.inspection.updatedAt, { true })
        remote = JSONObject(snapshot.request).put("version", 1).put("revision", 1).put("expert_status", "confirmed")
    }

    @After fun close() { db.close(); folder.deleteRecursively() }

    @Test fun importPreservesPhotosPlantsProvenanceAndRepeatIsIdempotent() {
        val api = downloadApi()
        val importer = SyncImporter(db, File(folder, "photos"), File(folder, "stage"))
        assertTrue(importer.importRecord(account, remote, api))
        assertFalse(importer.importRecord(account, remote, api))
        assertEquals(1, db.journal().inspectionCount())
        val saved = db.journal().inspections().single()
        assertEquals("Куст", saved.photos.single().plantName)
        assertEquals("confirmed", saved.inspection.expertStatus)
        assertEquals(source.readBytes().toList(), File(saved.photos.single().photo.localPath).readBytes().toList())
        val sourceRecord = JSONObject(saved.inspection.predictionJson).getJSONObject("agrovoice_sync_source").getJSONObject("record")
        assertTrue(SyncPayload.sameJson(remote, sourceRecord))
        // An unchanged imported record must not rewrite origin/plant/photo identity on the server.
        val state = db.sync().records(account).single()
        val engine = SyncEngine(db, { it.toPath().startsWith(File(folder, "photos").toPath()) })
        engine.enqueue(account, saved.inspection.id)
        assertEquals(state.request, db.sync().record(account, saved.inspection.id)!!.request)
        db.journal().editNote(saved.inspection.id, saved.inspection.updatedAt, "Моя правка", saved.inspection.updatedAt + 1)
        // Pulling the same ID never replaces the local correction.
        assertFalse(importer.importRecord(account, remote, api))
        assertEquals("Моя правка", db.journal().inspection(saved.inspection.id)!!.note)
    }

    @Test fun incompleteOrWrongPhotoDoesNotCreatePartialInspection() {
        val importer = SyncImporter(db, File(folder, "photos"), File(folder, "stage"))
        assertTrue(runCatching { importer.importRecord(account, remote, downloadApi(corrupt = true)) }.isFailure)
        assertEquals(0, db.journal().inspectionCount())
        assertTrue(db.sync().records(account).isEmpty())
        assertTrue(importer.importRecord(account, remote, downloadApi()))
    }

    @Test fun legacyRemoteWithoutEmbeddedModelUsesDeclaredServerModel() {
        val prediction = remote.getJSONObject("data").getJSONObject("prediction")
        prediction.remove("model")
        prediction.remove("model_sha256")
        prediction.remove("agrovoice_frames")
        val importer = SyncImporter(db, File(folder, "photos"), File(folder, "stage"))

        assertTrue(importer.importRecord(account, remote, downloadApi()))

        val saved = db.journal().inspections().single()
        val report = JSONObject(requireNotNull(saved.photos.single().photo.predictionJson))
        assertEquals("yolo26n", saved.inspection.modelVersion)
        assertEquals("a".repeat(64), saved.inspection.modelSha256)
        assertEquals(saved.inspection.modelVersion, report.getString("model"))
        assertEquals(saved.inspection.modelSha256, report.getString("model_sha256"))
    }

    @Test fun sameServerIdInDifferentAccountCreatesDifferentLocalIdentity() {
        val importer = SyncImporter(db, File(folder, "photos"), File(folder, "stage"))
        importer.importRecord(account, remote, downloadApi())
        importer.importRecord("https://other.example|another", remote, downloadApi())
        assertEquals(2, db.journal().inspectionCount())
        assertEquals(2, db.journal().inspections().flatMap { it.photos }.map { it.photo.plantId }.toSet().size)
    }

    @Test fun logoutDuringStagingPreparationPreventsDownload() {
        var active = true
        var downloads = 0
        val staging = object : File(folder, "cancel-stage") {
            override fun getUsableSpace(): Long {
                active = false // Logout occurs during the storage check, after the loop's first check.
                return Long.MAX_VALUE
            }
        }
        val api = object : SyncApi by downloadApi() {
            override fun download(id: String, target: File, bytes: Long) { downloads++ }
        }
        val failure = runCatching {
            SyncImporter(db, File(folder, "photos"), staging).importRecord(account, remote, api) { active }
        }.exceptionOrNull()
        assertTrue(failure is java.util.concurrent.CancellationException)
        assertEquals(0, downloads)
        assertEquals(0, db.journal().inspectionCount())
    }

    private fun downloadApi(corrupt: Boolean = false) = object : SyncApi {
        override fun push(update: JSONObject): JSONObject = throw AssertionError("Import must not push")
        override fun upload(id: String, file: File, sha: String) = throw AssertionError("Import must not upload")
        override fun pull(after: Long): JSONObject = throw AssertionError("Not used")
        override fun download(id: String, target: File, bytes: Long) {
            target.writeBytes(if (corrupt) byteArrayOf(9) else source.readBytes())
        }
    }
}
