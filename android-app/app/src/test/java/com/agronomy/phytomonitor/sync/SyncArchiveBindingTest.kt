package com.agronomy.phytomonitor.sync

import android.content.Context
import android.content.ContextWrapper
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncArchiveBindingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = "http://127.0.0.1:8028|fixture"
    private lateinit var directory: File
    private lateinit var restoredContext: Context
    private lateinit var restored: JournalDatabase
    private lateinit var remote: JSONObject
    private lateinit var bytes: ByteArray
    private val databases = mutableListOf<JournalDatabase>()

    @Before fun archiveRoundtrip() {
        directory = File(context.cacheDir, "archive-binding-${UUID.randomUUID()}").apply { mkdirs() }
        val origin = isolatedContext(File(directory, "origin"))
        restoredContext = isolatedContext(File(directory, "restored"))
        val source = db()
        restored = db()
        val image = File(directory, "source.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        bytes = image.readBytes()
        val original = JournalFixtures.legacyRecord("original-server-local", image.path, InspectionJournal.sha256(image))
        original.getJSONObject("prediction").put("model_sha256", "a".repeat(64))
        val parsed = JournalRecords.parse(original)
        val record = JournalRecords.toRecord(parsed, null, null, image.path)
        val details = InspectionWithDetails(record.inspection, null,
            listOf(PhotoWithDetections(record.photo, record.detections)))
        remote = JSONObject(SyncPayload.snapshot(account, details, 0, record.inspection.updatedAt, { true }).request)
            .put("version", 1).put("revision", 1).put("expert_status", "unreviewed")
        SyncImporter(source, File(origin.getExternalFilesDir(null), "photos"), File(origin.cacheDir, "stage"))
            .importRecord(account, remote, api())
        val archive = ByteArrayOutputStream()
        InspectionJournal(origin, source).exportArchive(archive)
        val report = InspectionJournal(restoredContext, restored).import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, report.added)
        assertTrue(restored.sync().records(account).isEmpty()) // Restore is not an opt-in to send.
    }

    @After fun close() { databases.forEach { it.close() }; directory.deleteRecursively() }

    @Test fun sameAccountRestoresServerAndPhotoIdentityWithoutDuplicate() {
        val local = restored.journal().inspections().single()
        val engine = engine()
        engine.enqueue(account, local.inspection.id)
        val queued = restored.sync().record(account, local.inspection.id)!!
        assertEquals(remote.getString("id"), queued.serverId)
        assertEquals(remote.getJSONObject("data").getJSONArray("photos").getJSONObject(0).getString("id"),
            JSONObject(queued.request).getJSONObject("data").getJSONArray("photos").getJSONObject(0).getString("id"))
        val server = api()
        engine.synchronize(account, server)
        assertEquals(0, server.created)
        assertEquals("synced", restored.sync().record(account, local.inspection.id)!!.state)
        assertEquals(1L, restored.sync().record(account, local.inspection.id)!!.serverVersion)
        assertEquals(local.inspection.predictionJson, restored.journal().inspection(local.inspection.id)!!.predictionJson)
    }

    @Test fun staleOrForgedArchiveCannotOverwriteExistingServerRecord() {
        val local = restored.journal().inspections().single().inspection
        // An untrusted archive can claim any version; it must not grant overwrite authority.
        val prediction = JSONObject(local.predictionJson)
        prediction.getJSONObject("agrovoice_sync_source").getJSONObject("record").put("version", 900)
        restored.journal().upsertInspection(local.copy(note = "Архивная правка", predictionJson = prediction.toString()))
        val engine = engine()
        engine.enqueue(account, local.id)
        val request = JSONObject(restored.sync().record(account, local.id)!!.request)
        assertEquals(remote.getString("id"), request.getString("id"))
        assertEquals(0, request.getInt("base_version"))
        val server = api()
        engine.synchronize(account, server)
        assertEquals("conflict", restored.sync().record(account, local.id)!!.state)
        assertEquals(0, server.created)
        assertEquals(0, server.overwritten)
        assertEquals("Архивная правка", restored.journal().inspection(local.id)!!.note)
    }

    @Test fun differentAccountDoesNotReuseImportedBinding() {
        val other = "https://other.example|fixture"
        val local = restored.journal().inspections().single().inspection
        engine().enqueue(other, local.id)
        val queued = restored.sync().record(other, local.id)!!
        assertNotEquals(remote.getString("id"), queued.serverId)
        assertEquals(0L, queued.serverVersion)
    }

    @Test fun newerServerVersionConflictsWithRestoredArchiveInsteadOfCreatingCopy() {
        val local = restored.journal().inspections().single().inspection
        remote.getJSONObject("data").put("note", "Новая серверная заметка")
        remote.put("version", 2).put("updated_at", SyncPayload.iso(local.updatedAt + 1000))
        val engine = engine()
        engine.enqueue(account, local.id)
        val server = api()
        engine.synchronize(account, server)
        assertEquals("conflict", restored.sync().record(account, local.id)!!.state)
        assertEquals(0, server.created)
        assertEquals(0, server.overwritten)
        assertEquals(local.note, restored.journal().inspection(local.id)!!.note)
    }

    @Test fun tamperedServerIdCannotRebindTheExistingLocalIdentity() {
        val local = restored.journal().inspections().single().inspection
        val prediction = JSONObject(local.predictionJson)
        prediction.getJSONObject("agrovoice_sync_source").getJSONObject("record").put("id", UUID.randomUUID().toString())
        restored.journal().upsertInspection(local.copy(predictionJson = prediction.toString()))
        assertTrue(runCatching { engine().enqueue(account, local.id) }.isFailure)
        assertTrue(restored.sync().records(account).isEmpty())
        assertEquals(local.note, restored.journal().inspection(local.id)!!.note)
    }

    @Test fun restoredBindingCannotTakeAnotherLocalRecordsServerId() {
        val local = restored.journal().inspections().single().inspection
        val occupied = local.copy(id = "already-bound", reportName = "already-bound.json")
        restored.journal().upsertInspection(occupied)
        val existing = SyncRecord(account, occupied.id, remote.getString("id"), 1, occupied.updatedAt,
            remote.toString(), "[]", state = "synced")
        restored.sync().save(existing)
        assertTrue(runCatching { engine().enqueue(account, local.id) }.isFailure)
        assertNull(restored.sync().record(account, local.id))
        assertEquals(existing, restored.sync().record(account, occupied.id))
    }

    private fun engine() = SyncEngine(restored, { it.toPath().startsWith(File(restoredContext.getExternalFilesDir(null), "photos").canonicalFile.toPath()) })
    private fun db() = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build().also { databases += it }
    private fun isolatedContext(root: File): Context = object : ContextWrapper(context) {
        override fun getExternalFilesDir(type: String?) = File(root, "files").apply { mkdirs() }
        override fun getFilesDir() = File(root, "internal").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    private fun api() = object : SyncApi {
        var created = 0
        var overwritten = 0
        override fun push(update: JSONObject): JSONObject {
            val sameId = update.getString("id") == remote.getString("id")
            val sameData = SyncPayload.sameJson(update.getJSONObject("data"), remote.getJSONObject("data"))
            val sameTime = SyncPayload.parseTime(update.getString("updated_at")) == SyncPayload.parseTime(remote.getString("updated_at"))
            val status = when {
                !sameId -> { created++; "accepted" }
                sameData && sameTime -> "unchanged"
                update.getLong("base_version") == remote.getLong("version") -> { overwritten++; "accepted" }
                else -> "conflict"
            }
            return JSONObject().put("status", status).put("inspection", remote)
        }
        override fun upload(id: String, file: File, sha: String) { }
        override fun pull(after: Long) = JSONObject().put("inspections", JSONArray()).put("next_after", after).put("has_more", false)
        override fun download(id: String, target: File, bytes: Long) { target.writeBytes(this@SyncArchiveBindingTest.bytes) }
    }
}
