package com.agronomy.phytomonitor.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncEngineTest {
    private lateinit var db: JournalDatabase
    private lateinit var engine: SyncEngine
    private lateinit var photo: File
    private val account = "http://127.0.0.1:8028|user-a"

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        photo = File.createTempFile("sync-fixture", ".jpg", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val parsed = JournalRecords.parse(JournalFixtures.legacyRecord("old-id", photo.path))
        val record = JournalRecords.toRecord(parsed, null, null, photo.path)
        db.journal().saveRecord(record.copy(inspection = record.inspection.copy(modelSha256 = "a".repeat(64))),
            SyncQueueEntity(entity = "inspection", entityId = "old-id", operation = "upsert", createdAt = 1))
        engine = SyncEngine(db, { it.parentFile == context.cacheDir }, { 1_800_000_000_000L })
    }

    @After fun close() { db.close(); photo.delete() }

    @Test fun doesNotEnrollExistingJournalUntilSelected() {
        val api = FakeApi()
        engine.synchronize(account, api)
        assertEquals(0, api.pushes.size)
        assertNull(db.sync().record(account, "old-id"))
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        assertEquals("synced", state().state)
        assertEquals(1, api.uploads)
        assertEquals("old-id", api.pushes.single().getJSONObject("data").getString("legacy_id"))
    }

    @Test fun repeatsIdenticalSnapshotAfterLostResponseAndPhotoFailure() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        api.losePushReply = true
        assertTrue(engine.synchronize(account, api))
        assertEquals("error", state().state)
        api.failPhoto = true
        assertTrue(engine.synchronize(account, api))
        assertNotEquals("synced", state().state)
        api.failPhoto = false
        assertFalse(engine.synchronize(account, api))
        assertEquals("synced", state().state)
        assertEquals(1, api.version)
        assertEquals(1, api.pushes.map { it.toString() }.toSet().size)
    }

    @Test fun concurrentEditSurvivesOldSnapshotAcknowledgement() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        api.duringPush = {
            val before = db.journal().inspection("old-id")!!
            db.journal().editNote("old-id", before.updatedAt, "Исправление", before.updatedAt + 1)
        }
        engine.synchronize(account, api)
        assertEquals("queued", state().state)
        assertEquals("Исправление", db.journal().inspection("old-id")!!.note)
        engine.synchronize(account, api)
        assertEquals("synced", state().state)
        assertEquals("Исправление", api.pushes.last().getJSONObject("data").getString("note"))
    }

    @Test fun conflictRequiresExplicitVersionedResolution() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        api.conflict = true
        engine.synchronize(account, api)
        assertEquals("conflict", state().state)
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        assertEquals(1, api.pushes.size)
        val seen = state().conflictJson!!
        engine.resolveConflict(account, "old-id", seen)
        api.conflict = false
        engine.synchronize(account, api)
        assertEquals(7, api.pushes.last().getInt("base_version"))
        assertEquals("synced", state().state)
    }

    @Test fun accountSwitchAndCancellationCannotSendMoreRequests() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        engine.synchronize("https://example.com|user-b", api)
        assertEquals(0, api.pushes.size)
        var active = true
        api.duringPush = { active = false }
        try { engine.synchronize(account, api) { active }; fail("must cancel") }
        catch (_: CancellationException) { }
        assertEquals(0, api.uploads)
        assertNotEquals("synced", state().state)
        engine.synchronize(account, api)
        assertEquals("synced", state().state)
    }

    @Test fun authenticationFailureStopsBatchAndKeepsLocalData() {
        val api = FakeApi().apply { unauthorized = true }
        engine.enqueue(account, "old-id")
        assertFalse(engine.synchronize(account, api))
        assertEquals("error", state().state)
        assertTrue(state().error!!.contains("Войти"))
        assertNotNull(db.journal().inspection("old-id"))
        assertEquals(0, api.uploads)
        assertTrue(engine.authenticationRequired)
    }

    @Test fun expiredTokenDuringStatusPollDoesNotRetryForeverOrLoseAcknowledgement() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        api.pullUnauthorized = true
        assertFalse(engine.synchronize(account, api))
        assertTrue(engine.authenticationRequired)
        assertEquals("synced", state().state)
    }

    @Test fun idsAreStableAndPhotoIdentityIncludesContent() {
        assertEquals(SyncIds.inspection("legacy"), SyncIds.inspection("legacy"))
        UUID.fromString(SyncIds.inspection("legacy"))
        assertNotEquals(SyncIds.photo("p", "a"), SyncIds.photo("p", "b"))
    }

    @Test fun expertOnlyVersionChangeDoesNotBecomeConflictOrUploadAgain() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        api.pulled = JSONObject(api.latest.toString()).put("version", 2).put("revision", 2).put("expert_status", "confirmed")
        engine.synchronize(account, api)
        assertEquals("synced", state().state)
        assertEquals(2L, state().serverVersion)
        assertEquals("confirmed", state().expertStatus)
        assertEquals(1, api.uploads)
        assertEquals(1, api.pushes.size)
    }

    @Test fun repeatOfUnchangedSyncedRecordDoesNotCreateAnotherServerVersion() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        val request = state().request
        engine.enqueue(account, "old-id")
        assertEquals(request, state().request)
        engine.synchronize(account, api)
        assertEquals(1, api.version)
    }

    @Test fun photoOutsideApprovedStorageIsNeverQueued() {
        engine = SyncEngine(db, { false })
        assertTrue(runCatching { engine.enqueue(account, "old-id") }.isFailure)
        assertNull(db.sync().record(account, "old-id"))
    }

    @Test fun logoutDuringSnapshotPreparationPreventsNextPush() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        val old = db.journal().inspection("old-id")!!
        db.journal().editNote(old.id, old.updatedAt, "Новая заметка", old.updatedAt + 1)
        var active = true
        engine = SyncEngine(db, { active = false; true })
        try { engine.synchronize(account, api) { active }; fail("Must cancel after snapshot") }
        catch (_: CancellationException) { }
        assertEquals(1, api.pushes.size)
        assertEquals(1, api.uploads)
    }

    @Test fun logoutDuringUploadPreparationPreventsPut() {
        val api = FakeApi()
        engine.enqueue(account, "old-id")
        var active = true
        engine = SyncEngine(db, { active = false; true })
        try { engine.synchronize(account, api) { active }; fail("Must cancel after hashing") }
        catch (_: CancellationException) { }
        assertEquals(1, api.pushes.size)
        assertEquals(0, api.uploads)
    }

    @Test fun confirmedVersionCannotQueueAPreviouslyUnseenEdit() {
        val before = db.journal().inspection("old-id")!!
        db.journal().editNote(before.id, before.updatedAt, "Изменено после вопроса", before.updatedAt + 1)
        assertTrue(runCatching { engine.enqueue(account, before.id, before.updatedAt) }.isFailure)
        assertNull(db.sync().record(account, before.id))
    }

    @Test fun logoutDuringEnqueuePreparationCannotEnrollForTheNextSession() {
        var active = true
        engine = SyncEngine(db, { active = false; true })
        val result = runCatching {
            engine.enqueue(account, "old-id") { write ->
                if (!active) throw CancellationException("Logged out")
                write()
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertNull(db.sync().record(account, "old-id"))
        val nextSession = FakeApi()
        engine.synchronize(account, nextSession)
        assertTrue(nextSession.pushes.isEmpty())
    }

    @Test fun logoutDuringConflictPreparationKeepsTheConflictForTheNextSession() {
        val api = FakeApi().apply { conflict = true }
        engine.enqueue(account, "old-id")
        engine.synchronize(account, api)
        val before = state()
        var active = true
        engine = SyncEngine(db, { active = false; true })
        val result = runCatching {
            engine.resolveConflict(account, "old-id", before.conflictJson!!) { write ->
                if (!active) throw CancellationException("Logged out")
                write()
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(before, state())
        val nextSession = FakeApi()
        engine.synchronize(account, nextSession)
        assertTrue(nextSession.pushes.isEmpty())
    }

    @Test fun expiredSessionCannotResetAnExistingErrorToQueued() {
        engine.enqueue(account, "old-id")
        engine.synchronize(account, FakeApi().apply { unauthorized = true })
        val before = state()
        val result = runCatching {
            engine.enqueue(account, "old-id") { throw CancellationException("Session expired") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(before, state())
    }

    @Test fun responseObjectOrderingDoesNotChangeEvidence() {
        assertTrue(SyncPayload.sameJson(JSONObject("{\"a\":1,\"b\":[2,3]}"), JSONObject("{\"b\":[2.0,3],\"a\":1.0}")))
    }

    private fun state() = db.sync().record(account, "old-id")!!

    private class FakeApi : SyncApi {
        val pushes = mutableListOf<JSONObject>()
        var uploads = 0
        var version = 0
        var losePushReply = false
        var failPhoto = false
        var conflict = false
        var unauthorized = false
        var pullUnauthorized = false
        var duringPush: (() -> Unit)? = null
        var latest: JSONObject? = null
        var pulled: JSONObject? = null
        override fun push(update: JSONObject): JSONObject {
            if (unauthorized) throw SyncHttpException(401)
            pushes += JSONObject(update.toString())
            duringPush?.also { duringPush = null }?.invoke()
            if (latest?.getJSONObject("data")?.toString() != update.getJSONObject("data").toString()) version++
            val remote = JSONObject(update.toString()).put("version", if (conflict) 7 else version)
                .put("expert_status", "unreviewed")
            latest = remote
            if (losePushReply) { losePushReply = false; throw IOException("lost response") }
            return JSONObject().put("status", if (conflict) "conflict" else "accepted").put("inspection", remote)
        }
        override fun upload(id: String, file: File, sha: String) {
            if (failPhoto) throw IOException("connection lost")
            uploads++
        }
        override fun pull(after: Long): JSONObject {
            if (pullUnauthorized) throw SyncHttpException(401)
            return JSONObject().put("inspections", JSONArray().apply { pulled?.let { put(it) } })
                .put("has_more", false).put("next_after", after)
        }
    }
}
