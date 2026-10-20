package com.agronomy.phytomonitor.sync

import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.journal.JournalDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable

/** Blocking operations run on Dispatchers.IO. Journal edits remain independent of the network. */
class SyncEngine(
    private val database: JournalDatabase,
    private val allowedPhoto: (File) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.sync()
    var authenticationRequired: Boolean = false
        private set

    fun enqueue(account: String, id: String, expectedUpdatedAt: Long? = null,
                commit: (() -> Unit) -> Unit = { it() }) = synchronized(lock) {
        val old = dao.record(account, id) ?: recoverArchiveBinding(account, id)
        if (old?.state == "conflict") return@synchronized
        if (old != null && old.state != "synced") {
            // Even after an error the server may have committed this exact snapshot.
            saveConfirmed(old.copy(state = "queued", error = null), expectedUpdatedAt, commit)
        } else {
            val candidate = snapshot(account, id, old)
            saveConfirmed(if (old != null && !changed(old, candidate)) old.copy(state = "queued", error = null) else candidate, expectedUpdatedAt, commit)
        }
    }

    /** Uses exactly the server version shown in the confirmation dialog, never a newer unseen one. */
    fun resolveConflict(account: String, id: String, seenConflict: String, expectedUpdatedAt: Long? = null,
                        commit: (() -> Unit) -> Unit = { it() }) = synchronized(lock) {
        val old = requireNotNull(dao.record(account, id))
        check(old.state == "conflict" && old.conflictJson == seenConflict) { "Конфликт изменился. Откройте его заново." }
        val remote = JSONObject(seenConflict).optJSONObject("inspection")
            ?: throw IllegalStateException("На сервере нет доступной версии. Нужна проверка серверной записи.")
        val remoteTime = SyncPayload.parseTime(remote.getString("updated_at")).toEpochMilli()
        val resolved = old.copy(serverVersion = remote.getLong("version"),
            request = JSONObject(old.request).put("updated_at", SyncPayload.iso(remoteTime)).toString())
        saveConfirmed(snapshot(account, id, resolved, minimumTime = maxOf(now(), remoteTime + 1)), expectedUpdatedAt, commit)
    }

    /** Returns true only for retryable work. No session means the caller never invokes this method. */
    fun synchronize(account: String, api: SyncApi, active: () -> Boolean = { true }): Boolean = synchronized(lock) {
        authenticationRequired = false
        var retry = false
        for (initial in dao.records(account)) {
            ensureActive(active)
            if (initial.state == "conflict") continue
            var current = initial
            try {
                if (current.state == "synced") {
                    val candidate = snapshot(account, current.localId, current)
                    if (!changed(current, candidate)) continue
                    current = candidate
                    dao.save(current)
                }
                dao.save(current.copy(state = "sending", error = null))
                val request = JSONObject(current.request)
                ensureActive(active)
                val result = api.push(request)
                ensureActive(active)
                if (result.getString("status") == "conflict") {
                    dao.save(current.copy(state = "conflict", conflictJson = result.toString(), error = null))
                    continue
                }
                require(result.getString("status") in setOf("accepted", "unchanged")) { "Неизвестный ответ сервера." }
                val remote = result.getJSONObject("inspection")
                check(remote.getString("id") == current.serverId && remote.getLong("version") > 0) { "Сервер вернул другой осмотр." }
                val files = JSONArray(current.photoFiles)
                for (index in 0 until files.length()) {
                    ensureActive(active)
                    val item = files.getJSONObject(index)
                    val file = File(item.getString("path")).canonicalFile
                    require(allowedPhoto(file) && file.isFile && file.length() == item.getLong("bytes") &&
                        InspectionJournal.sha256(file) == item.getString("sha256")) {
                        "Исходное фото недоступно или изменилось. Осмотр сохранён; восстановите фото из архива."
                    }
                    val photoId = item.getString("id")
                    val sha = item.getString("sha256")
                    ensureActive(active)
                    api.upload(photoId, file, sha)
                }
                ensureActive(active)
                val completed = current.copy(serverVersion = remote.getLong("version"), state = "synced",
                    error = null, expertStatus = remote.optString("expert_status", "unreviewed"))
                // Persist acknowledgement before building a newer snapshot (which can itself fail).
                dao.save(completed)
                val next = snapshot(account, current.localId, completed)
                if (changed(completed, next)) {
                    dao.save(next)
                    retry = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val latest = dao.record(account, current.localId) ?: current
                dao.save(latest.copy(state = "error", error = syncError(error)))
                if (error is SyncHttpException && error.status in setOf(401, 403)) {
                    authenticationRequired = true
                    return@synchronized false
                }
                retry = retry || (error is IOException && (error !is SyncHttpException || error.status >= 500 || error.status == 429))
            }
        }
        ensureActive(active)
        try {
            refreshExpertStatus(account, api, active)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed status poll must never revoke a completed record+photo acknowledgement.
            authenticationRequired = error is SyncHttpException && error.status in setOf(401, 403)
            retry = !authenticationRequired
        }
        retry
    }

    private fun refreshExpertStatus(account: String, api: SyncApi, active: () -> Boolean) {
        val enrolled = dao.records(account).associateBy { it.serverId }
        if (enrolled.isEmpty()) return
        var after = 0L
        do {
            ensureActive(active)
            val response = api.pull(after)
            ensureActive(active)
            val records = response.getJSONArray("inspections")
            for (index in 0 until records.length()) {
                val remote = records.getJSONObject(index)
                val localId = enrolled[remote.getString("id")]?.localId ?: continue
                val current = dao.record(account, localId) ?: continue
                if (current.state != "synced") continue
                if (current.serverVersion == remote.getLong("version")) {
                    dao.save(current.copy(expertStatus = remote.getString("expert_status")))
                } else if (remote.getLong("version") > current.serverVersion) {
                    val acknowledged = JSONObject(current.request)
                    val sameEvidence = SyncPayload.sameJson(acknowledged.getJSONObject("data"), remote.getJSONObject("data")) &&
                        SyncPayload.parseTime(acknowledged.getString("updated_at")) == SyncPayload.parseTime(remote.getString("updated_at"))
                    if (sameEvidence) {
                        dao.save(current.copy(serverVersion = remote.getLong("version"), expertStatus = remote.getString("expert_status")))
                    } else {
                        dao.save(current.copy(state = "conflict", conflictJson = JSONObject()
                            .put("status", "conflict").put("inspection", remote).toString()))
                    }
                }
            }
            val next = response.getLong("next_after")
            check(!response.getBoolean("has_more") || next > after) { "Сервер не продвинул страницу синхронизации." }
            after = next
        } while (response.getBoolean("has_more"))
    }

    private fun snapshot(account: String, id: String, old: SyncRecord?, minimumTime: Long = 0): SyncRecord {
        val details = database.runInTransaction(Callable { database.journal().inspectionDetails(id) })
            ?: throw IllegalStateException("Осмотр не найден. Ничего не отправлено.")
        val previousTime = old?.let { SyncPayload.parseTime(JSONObject(it.request).getString("updated_at")).toEpochMilli() } ?: 0
        val time = maxOf(details.inspection.updatedAt, minimumTime, previousTime + 1)
        return SyncPayload.snapshot(account, details, old?.serverVersion ?: 0, time, allowedPhoto, old)
    }

    private fun changed(before: SyncRecord, after: SyncRecord): Boolean =
        before.snapshotUpdatedAt != after.snapshotUpdatedAt ||
            !SyncPayload.sameJson(JSONObject(before.request).getJSONObject("data"), JSONObject(after.request).getJSONObject("data"))

    private fun recoverArchiveBinding(account: String, id: String): SyncRecord? {
        val details = database.journal().inspectionDetails(id) ?: return null
        val restored = SyncArchiveBinding.recover(account, details) ?: return null
        check(dao.records(account).none { it.serverId == restored.serverId && it.localId != id }) {
            "Серверная запись уже связана с другим локальным осмотром. Откройте его; восстановленная копия сохранена отдельно."
        }
        return restored
    }

    private fun saveConfirmed(record: SyncRecord, expectedUpdatedAt: Long?, commit: (() -> Unit) -> Unit) {
        // Snapshot preparation and hashing are finished before the session publication lock.
        commit {
            database.runInTransaction {
                check(expectedUpdatedAt == null || database.journal().inspection(record.localId)?.updatedAt == expectedUpdatedAt) {
                    "Осмотр изменился после вопроса. Запросите подтверждение заново."
                }
                dao.save(record)
            }
        }
    }

    private fun ensureActive(active: () -> Boolean) {
        if (!active() || Thread.currentThread().isInterrupted) throw CancellationException("Sync session ended")
    }

    companion object {
        // One worker at a time, including periodic/manual overlap; this is not a Room/network transaction.
        private val lock = Any()
    }
}
