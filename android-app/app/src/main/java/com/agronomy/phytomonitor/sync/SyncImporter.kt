package com.agronomy.phytomonitor.sync

import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.journal.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException

data class SyncImportReport(val added: Int, val known: Int, val errors: List<String>) {
    fun summary() = "Получено новых: $added; уже на устройстве: $known; не получено: ${errors.size}." +
        errors.take(3).joinToString(prefix = if (errors.isEmpty()) "" else "\n", separator = "\n")
}

/** Explicit download only. Remote paths and logical IDs are never used as filesystem paths. */
class SyncImporter(private val database: JournalDatabase, private val photos: File, private val staging: File) {
    fun importAll(account: String, api: SyncApi, active: () -> Boolean = { true }): SyncImportReport {
        var after = 0L
        var added = 0
        var known = 0
        val errors = mutableListOf<String>()
        do {
            ensureActive(active)
            val response = api.pull(after)
            val records = response.getJSONArray("inspections")
            for (index in 0 until records.length()) {
                val remote = records.getJSONObject(index)
                try {
                    if (importRecord(account, remote, api, active)) added++ else known++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errors += "${remote.optString("id").take(12)}: ${syncError(error)}"
                }
            }
            val next = response.getLong("next_after")
            check(!response.getBoolean("has_more") || next > after) { "Сервер не продвинул страницу синхронизации." }
            after = next
        } while (response.getBoolean("has_more"))
        return SyncImportReport(added, known, errors)
    }

    fun importRecord(account: String, remote: JSONObject, api: SyncApi, active: () -> Boolean = { true }): Boolean {
        ensureActive(active)
        val serverId = UUID.fromString(remote.getString("id")).toString()
        if (database.sync().records(account).any { it.serverId == serverId }) return false
        val localId = SyncIds.importedInspection(account, serverId)
        val data = remote.getJSONObject("data")
        val references = data.getJSONArray("photos")
        // Archive v2 currently exports one photo. Do not expose an apparently complete backup
        // for a downloaded series until the archive migration supports every frame.
        require(references.length() == 1) { "Осмотр с серией фото оставлен на сервере: сначала нужна поддержка резервной копии всех кадров." }
        require(data.getString("note").length <= MAX_NOTE && data.optString("plot").length <= MAX_PLOT) {
            "Осмотр оставлен на сервере: заметка или название участка длиннее поддерживаемого приложением."
        }
        require(data.getString("user_review") in REVIEWS) { "Серверная оценка пока не поддерживается приложением." }
        require(Regex("[0-9a-f]{64}").matches(data.getString("model_sha256"))) { "Неверная версия модели в серверном осмотре." }
        val prediction = data.getJSONObject("prediction")
        require(prediction.optJSONArray("detections") != null) { "Формат результата сервера не поддерживается: осмотр оставлен на сервере." }
        val files = JSONArray()
        check(staging.isDirectory || staging.mkdirs()) { "Не удалось подготовить загрузку фото." }
        for (index in 0 until references.length()) {
            ensureActive(active)
            val reference = references.getJSONObject(index)
            val photoId = UUID.fromString(reference.getString("id")).toString()
            val bytes = reference.getLong("bytes")
            val sha = reference.getString("sha256")
            require(bytes in 1..SyncPayload.MAX_PHOTO_BYTES && Regex("[0-9a-f]{64}").matches(sha)) { "Неверный размер или SHA серверного фото." }
            val target = File(photos, "server_${identity(account, "$serverId:$photoId:$sha")}.jpg")
            if (target.exists()) {
                check(target.isFile && target.length() == bytes && InspectionJournal.sha256(target) == sha) {
                    "Локальная копия серверного фото повреждена. Существующий файл сохранён; требуется восстановление."
                }
            } else {
                JournalStorage.requireSpace(staging, bytes)
                val temporary = File.createTempFile("sync-photo-", ".part", staging)
                try {
                    ensureActive(active)
                    api.download(photoId, temporary, bytes)
                    ensureActive(active)
                    check(temporary.length() == bytes && InspectionJournal.sha256(temporary) == sha) {
                        "Получено неполное или повреждённое фото. Осмотр не добавлен; повторите загрузку."
                    }
                    JournalStorage.copyPhoto(temporary, target)
                } finally { temporary.delete() }
            }
            files.put(JSONObject().put("id", photoId).put("path", target.canonicalPath).put("sha256", sha).put("bytes", bytes))
        }
        ensureActive(active)
        return database.runInTransaction(Callable {
            if (database.sync().records(account).any { it.serverId == serverId }) return@Callable false
            check(database.journal().inspection(localId) == null) { "Локальный ID уже занят. Существующий осмотр сохранён." }
            insert(account, localId, serverId, remote, files)
            true
        })
    }

    private fun insert(account: String, localId: String, serverId: String, remote: JSONObject, files: JSONArray) {
        val dao = database.journal()
        val data = remote.getJSONObject("data")
        val originalPrediction = data.getJSONObject("prediction")
        val origin = originalPrediction.optJSONObject("agrovoice_origin")
        val created = SyncPayload.parseTime(data.getString("created_at")).toEpochMilli()
        val updated = SyncPayload.parseTime(remote.getString("updated_at")).toEpochMilli()
        val plot = createPlot(account, data, origin, updated)
        val prediction = JSONObject(originalPrediction.toString())
            .put("photo", files.getJSONObject(0).getString("path"))
            .put("photo_sha256", files.getJSONObject(0).getString("sha256"))
            .put("model", data.getString("model_version")).put("model_sha256", data.getString("model_sha256"))
            .put("agrovoice_sync_source", JSONObject().put("account", account)
                .put("local_plot_name", plot?.name.orEmpty()).put("record", JSONObject(remote.toString())))
        val inspection = InspectionEntity(localId, plot?.id, row = nullableString(data, "row"), createdAt = created,
            lat = nullableDouble(data, "lat"), lon = nullableDouble(data, "lon"), accuracyM = nullableDouble(data, "accuracy_m"),
            note = data.getString("note"), userReview = data.getString("user_review"),
            expertStatus = remote.getString("expert_status"), modelVersion = data.getString("model_version"),
            modelSha256 = data.getString("model_sha256"), reportName = "server_$localId.json",
            reportSha256 = origin?.optString("report_sha256").orEmpty(), predictionJson = prediction.toString(), updatedAt = updated)
        dao.upsertInspection(inspection)
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val photoId = "$localId-${index.toString().padStart(2, '0')}"
            val plant = createPlant(account, plot?.id, origin, file, created, updated)
            dao.upsertPhoto(PhotoEntity(photoId, localId, plant?.id, file.getString("path"), file.getString("sha256"),
                originalPrediction.optInt("decoded_width"), originalPrediction.optInt("decoded_height"), uploaded = true))
            // Current Android reports describe the primary frame; never invent labels for other frames.
            if (index == 0) dao.insertDetections(JournalRecords.detections(prediction, photoId))
        }
        val request = JSONObject().put("id", serverId).put("base_version", remote.getLong("version"))
            .put("updated_at", remote.getString("updated_at")).put("data", JSONObject(data.toString()))
        database.sync().save(SyncRecord(account, localId, serverId, remote.getLong("version"), updated,
            request.toString(), files.toString(), state = "synced", expertStatus = remote.getString("expert_status")))
    }

    private fun createPlot(account: String, data: JSONObject, origin: JSONObject?, time: Long): PlotEntity? {
        val name = nullableString(data, "plot")?.takeIf { it.isNotBlank() } ?: return null
        val id = identity(account, "plot:${nullableString(origin, "plot_id") ?: name}")
        database.journal().plot(id)?.let { return it }
        val collision = database.journal().plotByName(name) != null
        val displayName = if (collision) "${name.take(100)} [${id.take(8)}]" else name
        val plot = PlotEntity(id, displayName, updatedAt = time)
        check(database.journal().insertPlot(plot) != -1L) { "Не удалось добавить участок без изменения существующего." }
        return plot
    }

    private fun createPlant(account: String, plotId: String?, origin: JSONObject?, file: JSONObject, created: Long, updated: Long): PlantEntity? {
        val plants = origin?.optJSONArray("plants") ?: return null
        val metadata = (0 until plants.length()).map { plants.getJSONObject(it) }.firstOrNull {
            SyncIds.photo(it.optString("photo_local_id"), file.getString("sha256")) == file.getString("id")
        } ?: return null
        val sourceId = nullableString(metadata, "plant_id") ?: return null
        val name = nullableString(metadata, "plant_name")?.takeIf { it.isNotBlank() } ?: return null
        require(name.length <= MAX_PLOT) { "Слишком длинное имя растения в серверном осмотре." }
        val id = identity(account, "plant:$sourceId")
        val existing = database.journal().plant(id)
        check(existing == null || existing.plotId == plotId && existing.name == name) { "Серверная ссылка на растение изменилась. Локальная история сохранена." }
        if (existing != null) return existing
        return PlantEntity(id, plotId, name, created, updated).also { database.journal().insertPlant(it) }
    }

    private fun ensureActive(active: () -> Boolean) {
        if (!active() || Thread.currentThread().isInterrupted) throw CancellationException("Sync import session ended")
    }

    private fun identity(account: String, source: String) = UUID.nameUUIDFromBytes("agrovoice:remote:$account:$source".toByteArray(Charsets.UTF_8)).toString()
    private fun nullableString(value: JSONObject?, key: String): String? = value?.takeUnless { it.isNull(key) }?.optString(key)?.takeUnless { it == "null" }
    private fun nullableDouble(value: JSONObject, key: String): Double? = if (value.isNull(key)) null else value.getDouble(key)
}
