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
        require(references.length() in 1..MAX_INSPECTION_FRAMES) { "Серверный осмотр должен содержать от 1 до $MAX_INSPECTION_FRAMES кадров." }
        require(data.getString("note").length <= MAX_NOTE && data.optString("plot").length <= MAX_PLOT) {
            "Осмотр оставлен на сервере: заметка или название участка длиннее поддерживаемого приложением."
        }
        require(data.getString("user_review") in REVIEWS) { "Серверная оценка пока не поддерживается приложением." }
        require(Regex("[0-9a-f]{64}").matches(data.getString("model_sha256"))) { "Неверная версия модели в серверном осмотре." }
        val prediction = data.getJSONObject("prediction")
        require(prediction.optJSONArray("detections") != null) { "Формат результата сервера не поддерживается: осмотр оставлен на сервере." }
        val frameMetadata = frameMetadata(prediction, references)
        validateEvidence(data, prediction, references, frameMetadata)
        validateFeedback(data, prediction, references, frameMetadata)
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
            files.put(JSONObject().put("id", photoId).put("frame_id", SyncIds.importedPhoto(account, serverId, photoId))
                .put("path", target.canonicalPath).put("sha256", sha).put("bytes", bytes))
        }
        ensureActive(active)
        return database.runInTransaction(Callable {
            if (database.sync().records(account).any { it.serverId == serverId }) return@Callable false
            check(database.journal().inspection(localId) == null) { "Локальный ID уже занят. Существующий осмотр сохранён." }
            insert(account, localId, serverId, remote, files, frameMetadata)
            true
        })
    }

    private fun insert(account: String, localId: String, serverId: String, remote: JSONObject, files: JSONArray,
                       frameMetadata: Map<String, JSONObject>) {
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
            val photoId = file.getString("frame_id")
            val metadata = frameMetadata[file.getString("id")]
            val framePrediction = if (metadata != null) metadata.optJSONObject("prediction") else if (index == 0) {
                JSONObject(originalPrediction.toString()).put("model", data.getString("model_version"))
                    .put("model_sha256", data.getString("model_sha256"))
            } else null
            val plant = createPlant(account, plot?.id, origin, file, created, updated, metadata)
            val reportName = metadata?.optString("report_name") ?: if (index == 0) inspection.reportName else ""
            val reportSha = metadata?.optString("report_sha256") ?: if (index == 0) inspection.reportSha256 else ""
            dao.upsertPhoto(PhotoEntity(photoId, localId, plant?.id, file.getString("path"), file.getString("sha256"),
                metadata?.optInt("width") ?: framePrediction?.optInt("decoded_width") ?: 0,
                metadata?.optInt("height") ?: framePrediction?.optInt("decoded_height") ?: 0, uploaded = true,
                frameOrder = index, frameRole = metadata?.getString("role") ?: "unspecified",
                reportName = reportName, reportSha256 = reportSha, predictionJson = framePrediction?.toString()))
            val evidence = framePrediction ?: metadata?.optJSONArray("stored_detections")?.let {
                JSONObject().put("detections", it)
            }
            if (evidence != null) dao.insertDetections(JournalRecords.detections(evidence, photoId))
            data.optJSONArray("frame_feedback")?.let { values ->
                val matching = (0 until values.length()).map { values.getJSONObject(it) }.filter {
                    it.getString("photo_sha256") == file.getString("sha256") && it.getString("report_sha256") == FrameFeedback.evidenceSha(reportSha)
                }
                require(matching.size <= 1) { "Повторяющаяся оценка кадра." }
                matching.singleOrNull()?.let { dao.upsertFrameFeedback(FrameFeedback.fromJson(photoId, it)) }
            }
        }
        val request = JSONObject().put("id", serverId).put("base_version", remote.getLong("version"))
            .put("updated_at", remote.getString("updated_at")).put("data", JSONObject(data.toString()))
        database.sync().save(SyncRecord(account, localId, serverId, remote.getLong("version"), updated,
            request.toString(), files.toString(), state = "synced", expertStatus = remote.getString("expert_status")))
    }

    private fun frameMetadata(prediction: JSONObject, references: JSONArray): Map<String, JSONObject> {
        val ids = (0 until references.length()).map { UUID.fromString(references.getJSONObject(it).getString("id")).toString() }
        require(ids.distinct().size == ids.size) { "Повторяющиеся серверные ID кадров." }
        if (!prediction.has("agrovoice_frames")) return emptyMap()
        val frames = prediction.optJSONArray("agrovoice_frames") ?: error("Неверные метаданные серии на сервере.")
        require(frames.length() == ids.size) { "Метаданные серии не содержат все кадры." }
        return (0 until frames.length()).associate { index ->
            val frame = frames.getJSONObject(index)
            val id = UUID.fromString(frame.getString("photo_id")).toString()
            require(id == ids[index] && frame.opt("order") is Number && frame.getDouble("order") == index.toDouble()) {
                "Порядок или ID кадров серверной серии не совпадают."
            }
            FrameRole.from(frame.getString("role"))
            if (!frame.isNull("prediction")) {
                val raw = frame.optJSONObject("prediction") ?: error("Неверный анализ кадра серии.")
                require(raw.optString("model").isNotBlank() && raw.optJSONArray("detections") != null) { "Неполный анализ кадра серии." }
            }
            require(frame.isNull("prediction") || !frame.has("stored_detections")) { "Неоднозначные рамки кадра серии." }
            if (frame.has("stored_detections")) {
                val stored = frame.optJSONArray("stored_detections") ?: error("Неверные сохранённые рамки кадра серии.")
                JournalRecords.detections(JSONObject().put("detections", stored), id)
            }
            id to frame
        }
    }

    private fun validateFeedback(data: JSONObject, prediction: JSONObject, references: JSONArray,
                                 frames: Map<String, JSONObject>) {
        if (!data.has("frame_feedback")) return
        val values = data.optJSONArray("frame_feedback") ?: error("Неверный список оценок кадров.")
        require(values.length() <= references.length()) { "Оценок больше, чем кадров." }
        val keys = mutableSetOf<Pair<String, String>>()
        for (index in 0 until values.length()) {
            val feedback = FrameFeedback.fromJson("", values.getJSONObject(index))
            val key = feedback.photoSha256 to feedback.reportSha256
            require(keys.add(key)) { "Повторяющаяся оценка кадра." }
            val matching = (0 until references.length()).count { photoIndex ->
                val photo = references.getJSONObject(photoIndex)
                val metadata = frames[photo.getString("id")]
                val reportSha = metadata?.optString("report_sha256") ?: if (photoIndex == 0) {
                    prediction.optJSONObject("agrovoice_origin")?.optString("report_sha256").orEmpty()
                } else ""
                val framePrediction = if (metadata != null) metadata.optJSONObject("prediction") else prediction.takeIf { photoIndex == 0 }
                photo.getString("sha256") == feedback.photoSha256 && FrameFeedback.evidenceSha(reportSha) == feedback.reportSha256 &&
                    feedback.matchesAnalysis(framePrediction)
            }
            require(matching == 1) { "Оценка не относится к единственному исходному анализу кадра." }
        }
    }

    private fun validateEvidence(data: JSONObject, prediction: JSONObject, references: JSONArray,
                                 frames: Map<String, JSONObject>) {
        for ((reportField, dataField) in listOf("model" to "model_version", "model_sha256" to "model_sha256")) {
            val original = prediction.optString(reportField)
            require(original.isBlank() || original == data.getString(dataField)) {
                "Модель исходного анализа не совпадает с серверными метаданными. Осмотр не добавлен."
            }
        }
        checkPhotoHash(prediction, references.getJSONObject(0))
        for (index in 0 until references.length()) {
            val reference = references.getJSONObject(index)
            val id = UUID.fromString(reference.getString("id")).toString()
            val report = frames[id]?.optJSONObject("prediction") ?: continue
            checkPhotoHash(report, reference)
            if (index == 0) {
                val primary = JSONObject(prediction.toString())
                    .put("model", data.getString("model_version"))
                    .put("model_sha256", data.getString("model_sha256"))
                require(JournalRecords.sameJson(analysis(primary), analysis(report))) {
                    "Исходный анализ осмотра не совпадает с анализом первого кадра. Осмотр не добавлен."
                }
            }
        }
    }

    private fun checkPhotoHash(prediction: JSONObject, reference: JSONObject) {
        val sha = prediction.optString("photo_sha256")
        require(sha.isBlank() || sha == reference.getString("sha256")) {
            "Фото серверного кадра не соответствует SHA исходного анализа. Осмотр не добавлен."
        }
    }

    private fun analysis(prediction: JSONObject): JSONObject = JSONObject(prediction.toString()).apply {
        listOf("photo", "photo_path", "photo_sha256", "agrovoice_frames", "agrovoice_origin", "agrovoice_sync_source")
            .forEach(::remove)
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

    private fun createPlant(account: String, plotId: String?, origin: JSONObject?, file: JSONObject, created: Long, updated: Long,
                            frame: JSONObject?): PlantEntity? {
        val metadata = frame ?: origin?.optJSONArray("plants")?.let { plants ->
            (0 until plants.length()).map { plants.getJSONObject(it) }.firstOrNull {
                SyncIds.photo(it.optString("photo_local_id"), file.getString("sha256")) == file.getString("id")
            }
        } ?: return null
        val sourceId = nullableString(metadata, "plant_id") ?: return null
        val name = nullableString(metadata, "plant_name")?.takeIf { it.isNotBlank() } ?: return null
        require(name.length <= MAX_PLOT) { "Слишком длинное имя растения в серверном осмотре." }
        val id = SyncIds.importedPlant(account, sourceId)
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
