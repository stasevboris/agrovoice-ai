package com.agronomy.phytomonitor

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.journal.*
import com.agronomy.phytomonitor.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Only an isolated DB/photo and the coordinator's isolated server account are used. */
@RunWith(AndroidJUnit4::class)
class SyncDeviceTest {
    @Test fun offlineQueueRetryPhotosConflictAndAccountIsolation() {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("syncOrigin")
        val username = arguments.getString("syncUsername")
        val password = arguments.getString("syncPassword")
        assumeTrue("Requires explicitly supplied isolated fixture server", origin != null && username != null && password != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureId = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "sync-device-$fixtureId").apply { mkdirs() }
        val name = "sync-device-$fixtureId.db"
        var database = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
        val photo = File(directory, "fixture.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())) }
        try {
            val now = System.currentTimeMillis()
            val photoSha = InspectionJournal.sha256(photo)
            val inspection = InspectionEntity("device-$fixtureId", null, createdAt = now,
                note = "Изолированный тест синхронизации", userReview = "uncertain", modelVersion = "test-fixture",
                modelSha256 = "a".repeat(64), reportName = "$fixtureId.json", reportSha256 = "b".repeat(64),
                predictionJson = JSONObject().put("detections", org.json.JSONArray()).put("test_fixture", true).toString(), updatedAt = now)
            val record = InspectionRecord(inspection, PhotoEntity("p-$fixtureId", inspection.id,
                localPath = photo.path, sha256 = photoSha, width = 1, height = 1), emptyList())
            database.journal().saveRecord(record, SyncQueueEntity(entity = "inspection", entityId = inspection.id, operation = "upsert", createdAt = now))
            val token = SyncHttpClient(origin!!).login(username!!, password!!).getString("access_token")
            val transport = SyncHttpClient(origin, token)
            val account = "${transport.origin}|${transport.profile().getString("id")}"
            var engine = SyncEngine(database, { it.parentFile == directory.canonicalFile })
            engine.enqueue(account, inspection.id)
            assertEquals("queued", database.sync().record(account, inspection.id)!!.state)
            // A different account must not see or transmit this outbox.
            assertTrue(database.sync().records("${transport.origin}|another-user").isEmpty())
            var lostReply = true
            var failPhoto = true
            val interrupted = object : SyncApi by transport {
                override fun push(update: JSONObject): JSONObject {
                    val result = transport.push(update)
                    if (lostReply) { lostReply = false; throw IOException("test: reply lost after commit") }
                    return result
                }
                override fun upload(id: String, file: File, sha: String) {
                    if (failPhoto) throw IOException("test: offline before photo")
                    transport.upload(id, file, sha)
                }
            }
            assertTrue(engine.synchronize(account, interrupted))
            database.close()
            database = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
            engine = SyncEngine(database, { it.parentFile == directory.canonicalFile })
            assertTrue(engine.synchronize(account, interrupted))
            assertEquals("error", database.sync().record(account, inspection.id)!!.state)
            failPhoto = false
            assertFalse(engine.synchronize(account, interrupted))
            var state = database.sync().record(account, inspection.id)!!
            assertEquals("synced", state.state)
            assertEquals(1L, state.serverVersion)
            engine.enqueue(account, inspection.id)
            engine.synchronize(account, transport)
            assertEquals(1L, database.sync().record(account, inspection.id)!!.serverVersion)

            // Simulate a separate client editing the same server version.
            val otherClient = JSONObject(state.request).put("base_version", state.serverVersion)
                .put("updated_at", java.time.Instant.ofEpochMilli(now + 10_000).toString())
            otherClient.getJSONObject("data").put("note", "Правка другого клиента")
            assertEquals("accepted", transport.push(otherClient).getString("status"))
            database.journal().editNote(inspection.id, inspection.updatedAt, "Локальная правка", now + 20_000)
            engine.synchronize(account, transport)
            state = database.sync().record(account, inspection.id)!!
            assertEquals("conflict", state.state)
            assertEquals("Локальная правка", database.journal().inspection(inspection.id)!!.note)
            engine.resolveConflict(account, inspection.id, state.conflictJson!!)
            engine.synchronize(account, transport)
            state = database.sync().record(account, inspection.id)!!
            assertEquals("synced", state.state)
            assertEquals(3L, state.serverVersion)

            val secondName = "sync-receiver-$fixtureId.db"
            val second = Room.databaseBuilder(context, JournalDatabase::class.java, secondName).build()
            try {
                var after = 0L
                var remote: JSONObject? = null
                do {
                    val page = transport.pull(after)
                    val items = page.getJSONArray("inspections")
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.getString("id") == state.serverId) remote = item
                    }
                    after = page.getLong("next_after")
                } while (remote == null && page.getBoolean("has_more"))
                val receivedPhotos = File(directory, "received")
                val importer = SyncImporter(second, receivedPhotos, File(directory, "stage"))
                assertTrue(importer.importRecord(account, requireNotNull(remote), transport))
                assertFalse(importer.importRecord(account, requireNotNull(remote), transport))
                val imported = second.journal().inspections().single()
                assertEquals(photoSha, InspectionJournal.sha256(File(imported.photos.single().photo.localPath)))
                val receiver = SyncEngine(second, { it.toPath().startsWith(receivedPhotos.canonicalFile.toPath()) })
                receiver.enqueue(account, imported.inspection.id)
                receiver.synchronize(account, transport)
                assertEquals(3L, second.sync().record(account, imported.inspection.id)!!.serverVersion)
                second.journal().editNote(imported.inspection.id, imported.inspection.updatedAt, "Правка полученного осмотра", now + 30_000)
                receiver.synchronize(account, transport)
                assertEquals(4L, second.sync().record(account, imported.inspection.id)!!.serverVersion)
                assertEquals(state.serverId, second.sync().record(account, imported.inspection.id)!!.serverId)
            } finally { second.close(); context.deleteDatabase(secondName) }

            val report = JSONObject().put("fixture_id", fixtureId).put("origin", origin)
                .put("isolated_database", name).put("lost_reply_retried", true).put("photo_failure_not_synced", true)
                .put("restart_preserves_queue", true).put("repeat_version", 1).put("resolved_version", state.serverVersion)
                .put("conflict_local_data_preserved", true).put("other_account_empty", true)
                .put("download_sha_verified", true).put("repeated_import_no_duplicate", true)
                .put("imported_edit_keeps_server_id", true)
                .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("transport", "HTTP via coordinator configured adb reverse; no Wi-Fi claim")
            val reports = File(context.getExternalFilesDir(null) ?: context.filesDir, "test-reports").apply { mkdirs() }
            File(reports, "sync-device-report.json").writeText(report.toString(2))
        } finally {
            database.close()
            context.deleteDatabase(name)
            photo.delete()
            directory.deleteRecursively()
        }
    }

    @Test fun realConnectionDropDuringPhotoCanRetryWithoutDuplicate() {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("faultOrigin")
        assumeTrue("Requires a one-shot partial-upload fault proxy", origin != null)
        val username = requireNotNull(arguments.getString("syncUsername"))
        val password = requireNotNull(arguments.getString("syncPassword"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "sync-fault-$fixture").apply { mkdirs() }
        val name = "sync-fault-$fixture.db"
        val database = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
        try {
            val photo = File(directory, "network.jpg").apply { writeBytes(ByteArray(512 * 1024) { (it % 251).toByte() }) }
            val sha = InspectionJournal.sha256(photo)
            val now = System.currentTimeMillis()
            val inspection = InspectionEntity("fault-$fixture", null, createdAt = now, note = "Изолированный обрыв сети",
                userReview = "uncertain", modelVersion = "network-fixture", modelSha256 = "a".repeat(64),
                reportName = "$fixture.json", reportSha256 = "b".repeat(64), predictionJson = "{\"detections\":[]}", updatedAt = now)
            database.journal().saveRecord(InspectionRecord(inspection,
                PhotoEntity("fault-photo-$fixture", inspection.id, localPath = photo.path, sha256 = sha, width = 1, height = 1), emptyList()),
                SyncQueueEntity(entity = "inspection", entityId = inspection.id, operation = "upsert", createdAt = now))
            val token = SyncHttpClient(origin!!).login(username, password).getString("access_token")
            val api = SyncHttpClient(origin, token)
            val account = "${api.origin}|${api.profile().getString("id")}"
            val engine = SyncEngine(database, { it.parentFile == directory.canonicalFile })
            engine.enqueue(account, inspection.id)
            engine.synchronize(account, api)
            val interrupted = database.sync().record(account, inspection.id)!!
            assertEquals("error", interrupted.state)
            val photoId = org.json.JSONArray(interrupted.photoFiles).getJSONObject(0).getString("id")
            val download = File(directory, "download.jpg")
            val missing = runCatching { api.download(photoId, download, photo.length()) }.exceptionOrNull()
            assertTrue(missing is SyncHttpException && missing.status == 404)
            engine.synchronize(account, api)
            val complete = database.sync().record(account, inspection.id)!!
            assertEquals("synced", complete.state)
            assertEquals(1L, complete.serverVersion)
            assertEquals(interrupted.request, complete.request)
            api.download(photoId, download, photo.length())
            assertEquals(sha, InspectionJournal.sha256(download))
            val reports = File(context.getExternalFilesDir(null) ?: context.filesDir, "test-reports").apply { mkdirs() }
            File(reports, "sync-network-fault-report.json").writeText(JSONObject()
                .put("fixture_id", fixture).put("origin", origin).put("device", android.os.Build.MODEL)
                .put("real_transport_error_state", interrupted.state).put("photo_before_retry_http", 404)
                .put("retry_state", complete.state).put("server_version", complete.serverVersion)
                .put("same_snapshot_retried", true).put("download_matches_source_sha", true).toString(2))
        } finally {
            database.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
