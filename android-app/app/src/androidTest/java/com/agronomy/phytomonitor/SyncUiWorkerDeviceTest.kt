package com.agronomy.phytomonitor

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.sync.*
import com.agronomy.phytomonitor.voice.VoiceAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Run phases separately with force-stop between them, only on -PisolateApp=true. */
class SyncUiWorkerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val marker get() = File(context.filesDir, "sync-ui-fixture.json")
    private val db get() = JournalDatabase.get(context)
    private val store get() = SyncSessionStore.get(context)

    @Before fun requireIsolatedApplication() {
        check(context.packageName == "com.agronomy.phytomonitor.verification") { "This test must never run against Boris's production package" }
        compose.waitUntil(60_000) { !compose.activity.fieldModel.busy && !compose.activity.journalModel.state.value.busy }
    }

    @Test fun loginAndSendThroughRealWorker() {
        check(!marker.exists()) { "Finish cleanupFixture before starting another isolated run" }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        check(connectivity.activeNetwork != null) { "NetworkType.CONNECTED unavailable; do not substitute direct SyncEngine execution" }
        val fixture = UUID.randomUUID().toString()
        val photo = File(context.getExternalFilesDir(null), "photos/ui-$fixture.png").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).let { bitmap ->
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val report = File(context.getExternalFilesDir(null), "results/ui-$fixture.json").apply { parentFile!!.mkdirs() }
        report.writeText(JSONObject().put("photo", photo.path).put("photo_sha256", InspectionJournal.sha256(photo))
            .put("model", "isolated-worker-fixture").put("model_sha256", "a".repeat(64))
            .put("created_epoch_ms", System.currentTimeMillis()).put("decoded_width", 16).put("decoded_height", 16)
            .put("detections", JSONArray()).put("test_fixture", true).toString())
        val record = InspectionJournal(context).save(report, "TEST: isolated WorkManager $fixture", "uncertain", "TEST: sync UI $fixture")
        val saved = JSONObject().put("fixture", fixture).put("local_id", record.id).put("photo", photo.path).put("report", report.path)
        marker.writeText(saved.toString())
        openSettings()
        loginWithKeyboard()
        val session = requireNotNull(store.session.value)
        assertEncrypted(session)
        saved.put("account", session.account).put("token_sha256", sha(session.token)).put("origin", session.origin)
        marker.writeText(saved.toString())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.activity.syncModel.state.value.generation == session.generation }
        assertEquals(session.generation, store.session.value!!.generation)
        press(VoiceAction.BACK)
        compose.waitUntil(10_000) { compose.activity.currentState().screen == Screen.MAIN }
        press(VoiceAction.JOURNAL)
        compose.waitUntil(20_000) { compose.activity.journalModel.state.value.visible.any { it.id == record.id } && !compose.activity.currentState().busy }
        val index = compose.activity.journalModel.state.value.visible.indexOfFirst { it.id == record.id } + 1
        press(VoiceAction.JOURNAL_NUMBER, index.toString())
        compose.waitUntil(20_000) { compose.activity.journalModel.state.value.opened?.details?.inspection?.id == record.id }
        // A transient login/voice snackbar can cover the scrolled button's tap target.
        compose.waitUntil(30_000) { compose.activity.fieldModel.message == null && !compose.activity.currentState().busy }
        val started = SystemClock.elapsedRealtime()
        evidence("before_queue_action", actionDiagnostics())
        compose.onNodeWithText("Отправить осмотр и фото").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) {
                db.sync().record(session.account, record.id) != null
            }
        } finally { evidence("queue_action", actionDiagnostics()) }
        assertNotNull("UI did not enqueue; see queue_action diagnostics", db.sync().record(session.account, record.id))
        compose.waitUntil(60_000) { db.sync().record(session.account, record.id)?.state == "synced" }
        val elapsed = SystemClock.elapsedRealtime() - started
        awaitCompletedWorker()
        val synced = db.sync().record(session.account, record.id)!!
        val api = SyncHttpClient(session.origin, session.token)
        val remote = findRemote(api, synced.serverId)
        assertEquals(1L, remote.getLong("version"))
        val reference = remote.getJSONObject("data").getJSONArray("photos").getJSONObject(0)
        val downloaded = File(context.cacheDir, "ui-download-$fixture.png")
        api.download(reference.getString("id"), downloaded, reference.getLong("bytes"))
        assertEquals(InspectionJournal.sha256(photo), InspectionJournal.sha256(downloaded))
        downloaded.delete()
        compose.onNodeWithText("Повторить отправку").performScrollTo().performClick()
        compose.waitUntil(20_000) { !compose.activity.syncModel.state.value.busy }
        awaitCompletedWorker()
        assertEquals(1L, findRemote(api, synced.serverId).getLong("version"))
        saved.put("server_id", synced.serverId).put("server_version", 1)
        marker.writeText(saved.toString())
        evidence("phase1", JSONObject().put("ui_login", true).put("activity_recreated", true)
            .put("real_worker", true).put("queue_to_synced_ms", elapsed).put("photo_sha_matches", true)
            .put("repeat_server_version", 1).put("encrypted_preferences_no_plaintext", true)
            .put("work", workEvidence()).put("package", context.packageName))
    }

    @Test fun restoreKeystoreRejectExpiredWorkerAndLogout() {
        val saved = JSONObject(marker.readText())
        val restored = requireNotNull(store.session.value) { "Keystore session was not restored after process restart" }
        assertEquals(saved.getString("account"), restored.account)
        assertEquals(saved.getString("token_sha256"), sha(restored.token))
        assertEncrypted(restored)
        openSettings()
        loginWithKeyboard()
        val fresh = requireNotNull(store.session.value)
        assertNotEquals(restored.generation, fresh.generation)
        assertFalse(store.expire(restored.generation) { fail("Old worker must not cancel the fresh account") })
        assertEquals(fresh.generation, store.session.value!!.generation)

        // The real server rejects this isolated expired-token fixture with HTTP401.
        val invalid = fresh.copy(token = "invalid-isolated-fixture-token", generation = UUID.randomUUID().toString())
        assertTrue(store.completeLogin(store.beginLogin(), invalid, { true }) { SyncWork.request(context) })
        compose.waitUntil(60_000) { store.session.value == null && store.authenticationRequired.value }
        assertEquals("synced", db.sync().record(fresh.account, saved.getString("local_id"))!!.state)
        loginWithKeyboard()
        awaitUnobstructedControls()
        compose.onNodeWithText("Выйти и остановить отправку").performScrollTo().performClick()
        compose.waitUntil(20_000) { store.session.value == null && !compose.activity.syncModel.state.value.busy }
        assertFalse(store.authenticationRequired.value)
        val queued = db.sync().record(fresh.account, saved.getString("local_id"))!!
        db.sync().save(queued.copy(state = "queued")) // Simulate an old durable task waiting at logout.
        SyncWork.request(context)
        awaitCompletedWorker()
        assertEquals("queued", db.sync().record(fresh.account, saved.getString("local_id"))!!.state)
        evidence("phase2", JSONObject().put("keystore_process_restore", true).put("old_generation_cannot_expire_new_session", true)
            .put("real_http401_stops_session", true).put("logout_clears_session", true).put("logged_out_worker_did_not_send", true)
            .put("work", workEvidence()))
    }

    @Test fun loggedOutProcessDoesNotSendStaleQueue() {
        val saved = JSONObject(marker.readText())
        assertNull(store.session.value)
        SyncWork.request(context)
        awaitCompletedWorker()
        assertEquals("queued", db.sync().record(saved.getString("account"), saved.getString("local_id"))!!.state)
        val origin = saved.getString("origin")
        // Read-only verification token is never saved as an application session.
        val token = SyncHttpClient(origin).login(required("syncUsername"), required("syncPassword")).getString("access_token")
        assertEquals(saved.getLong("server_version"), findRemote(SyncHttpClient(origin, token), saved.getString("server_id")).getLong("version"))
        assertNull(store.session.value)
        evidence("phase3", JSONObject().put("logout_survives_process_restart", true).put("stale_queue_not_sent", true)
            .put("server_version_unchanged", true).put("work", workEvidence()))
    }

    @Test fun cleanupFixture() {
        store.logout { SyncWork.cancel(context) }
        if (!marker.exists()) return
        val saved = JSONObject(marker.readText())
        val id = saved.getString("local_id")
        InspectionJournal(context).deleteForTest(id)
        db.openHelper.writableDatabase.execSQL("DELETE FROM sync_record WHERE localId = ?", arrayOf(id))
        for (key in listOf("photo", "report")) {
            val file = File(saved.getString(key)).canonicalFile
            check(file.toPath().startsWith(context.getExternalFilesDir(null)!!.canonicalFile.toPath()) && file.name.contains(saved.getString("fixture")))
            file.delete()
        }
        marker.delete()
    }

    private fun openSettings() {
        press(VoiceAction.SYNC_SETTINGS)
        compose.waitUntil(10_000) { compose.activity.currentState().screen == Screen.SETTINGS }
    }

    private fun loginWithKeyboard() {
        awaitUnobstructedControls()
        val previousGeneration = store.session.value?.generation
        compose.onNodeWithText("Адрес сервера").performScrollTo().performTextReplacement(required("syncOrigin"))
        compose.onNodeWithText("Логин").performScrollTo().performTextReplacement(required("syncUsername"))
        compose.onNodeWithText("Пароль (не сохраняется)").performScrollTo().performTextReplacement(required("syncPassword"))
        compose.onNodeWithText("Войти").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            store.session.value?.generation?.let { it != previousGeneration } == true &&
                !compose.activity.syncModel.state.value.busy
        }
    }

    private fun awaitUnobstructedControls() {
        compose.waitUntil(30_000) {
            compose.activity.fieldModel.message == null && !compose.activity.syncModel.state.value.busy &&
                compose.activity.syncModel.state.value.generation == store.session.value?.generation
        }
    }

    private fun assertEncrypted(session: SyncSession) {
        val xml = File(context.applicationInfo.dataDir, "shared_prefs/sync_session.xml").readText()
        assertFalse(xml.contains(session.token))
        assertFalse(xml.contains(required("syncPassword")))
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("agrovoice_sync_v1"))
    }

    private fun press(action: VoiceAction, value: String = "") = compose.runOnUiThread {
        compose.activity.actions.dispatch(ActionRequest(action, value, ActionSource.BUTTON))
    }

    private fun awaitCompletedWorker() {
        compose.waitUntil(60_000) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork("agrovoice_sync").get(5, TimeUnit.SECONDS)
                .let { it.isNotEmpty() && it.all { work -> work.state.isFinished } && it.any { work -> work.state == WorkInfo.State.SUCCEEDED } }
        }
    }

    private fun workEvidence(): JSONArray = JSONArray().apply {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("agrovoice_sync").get(5, TimeUnit.SECONDS).forEach {
            put(JSONObject().put("id", it.id.toString()).put("state", it.state.name).put("attempts", it.runAttemptCount))
        }
    }

    private fun findRemote(api: SyncHttpClient, id: String): JSONObject {
        var after = 0L
        do {
            val page = api.pull(after)
            val records = page.getJSONArray("inspections")
            for (index in 0 until records.length()) if (records.getJSONObject(index).getString("id") == id) return records.getJSONObject(index)
            after = page.getLong("next_after")
        } while (page.getBoolean("has_more"))
        error("Fixture not found on server")
    }

    private fun required(name: String) = requireNotNull(args.getString(name)) { "Supply isolated fixture argument $name" }
    private fun actionDiagnostics(): JSONObject {
        var result = JSONObject()
        compose.runOnUiThread {
            val activity = compose.activity
            val app = activity.currentState()
            result = JSONObject().put("screen", app.screen.name).put("busy", app.busy)
                .put("activity_identity", System.identityHashCode(activity))
                .put("inspection_open", app.inspectionOpen).put("opened_id", app.openedInspectionId)
                .put("authenticated", app.syncGeneration != null).put("sync_busy", activity.syncModel.state.value.busy)
                .put("sync_message", activity.syncModel.state.value.message).put("sync_error", activity.syncModel.state.value.messageIsError)
                .put("field_message", activity.fieldModel.message).put("field_error", activity.fieldModel.messageIsError)
                .put("lifecycle", activity.lifecycle.currentState.name)
                .put("verdict_now", com.agronomy.phytomonitor.actions.ActionPolicy.resolve(
                    ActionRequest(VoiceAction.SYNC_CURRENT, source = ActionSource.BUTTON), app).toString())
        }
        return result
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun evidence(phase: String, value: JSONObject) {
        val file = File(context.getExternalFilesDir(null), "test-reports/sync-ui-worker.json").apply { parentFile!!.mkdirs() }
        val report = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        file.writeText(report.put(phase, value).toString(2))
    }
}
