package com.agronomy.phytomonitor.sync

import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.journal.InspectionWithDetails
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

object SyncIds {
    fun inspection(local: String) = uuid("inspection:$local")
    fun photo(local: String, sha: String) = uuid("photo:$local:$sha")
    fun importedInspection(account: String, serverId: String) = UUID.nameUUIDFromBytes(
        "agrovoice:remote:$account:inspection:$serverId".toByteArray(Charsets.UTF_8)).toString()
    private fun uuid(value: String) = UUID.nameUUIDFromBytes("agrovoice:v1:$value".toByteArray(Charsets.UTF_8)).toString()
}

internal object SyncPayload {
    const val MAX_PHOTO_BYTES = 10L * 1024 * 1024

    fun snapshot(account: String, details: InspectionWithDetails, version: Long,
                 updatedAt: Long, allowedPhoto: (File) -> Boolean, previous: SyncRecord? = null): SyncRecord {
        val inspection = details.inspection
        require(Regex("[0-9a-f]{64}").matches(inspection.modelSha256)) {
            "У старого результата нет полного SHA модели. Он сохранён локально; для отправки нужен новый анализ."
        }
        require(details.photos.isNotEmpty() && details.photos.size <= 10) { "Для отправки нужны от 1 до 10 фото." }
        val photos = JSONArray()
        val files = JSONArray()
        val previousFiles = previous?.let { JSONArray(it.photoFiles) }
        details.photos.sortedBy { it.photo.id }.forEach { detail ->
            val photo = detail.photo
            val file = File(photo.localPath).canonicalFile
            require(allowedPhoto(file) && file.isFile) { "Фото недоступно в хранилище AgroVoice. Восстановите его из архива." }
            require(file.length() in 1..MAX_PHOTO_BYTES) { "Фото пустое или больше лимита сервера 10 МиБ. Исходный осмотр сохранён." }
            val sha = InspectionJournal.sha256(file)
            require(photo.sha256.isBlank() || photo.sha256 == sha) { "Фото изменилось после сохранения. Восстановите исходное фото из архива." }
            val previousPhoto = previousFiles?.let { values -> (0 until values.length()).map { values.getJSONObject(it) }
                .firstOrNull { it.getString("path") == file.path && it.getString("sha256") == sha } }
            val id = previousPhoto?.getString("id") ?: SyncIds.photo(photo.id, sha)
            photos.put(JSONObject().put("id", id).put("sha256", sha).put("bytes", file.length()))
            files.put(JSONObject().put("id", id).put("path", file.path).put("sha256", sha).put("bytes", file.length()))
        }
        val storedPrediction = JSONObject(inspection.predictionJson)
        val imported = storedPrediction.optJSONObject("agrovoice_sync_source")
        val sourceData = imported?.getJSONObject("record")?.getJSONObject("data")
        val prediction = sourceData?.getJSONObject("prediction")?.let { JSONObject(it.toString()) } ?: storedPrediction
        // Absolute device paths are local implementation details, not portable evidence.
        prediction.remove("photo")
        prediction.remove("photo_path")
        val plants = JSONArray()
        details.photos.sortedBy { it.photo.id }.forEach { detail ->
            plants.put(JSONObject().put("photo_local_id", detail.photo.id)
                .put("plant_id", detail.photo.plantId ?: JSONObject.NULL)
                .put("plant_name", detail.plantName ?: JSONObject.NULL))
        }
        if (sourceData == null) {
            prediction.put("agrovoice_origin", JSONObject().put("report_name", inspection.reportName)
                .put("report_sha256", inspection.reportSha256).put("plot_id", inspection.plotId ?: JSONObject.NULL)
                .put("plants", plants))
        }
        val plotName = if (imported != null && details.plot?.name.orEmpty() == imported.optString("local_plot_name")) {
            sourceData!!.opt("plot") ?: JSONObject.NULL
        } else details.plot?.name ?: JSONObject.NULL
        val data = (sourceData?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("legacy_id", sourceData?.opt("legacy_id") ?: inspection.id)
            .put("created_at", sourceData?.getString("created_at") ?: iso(inspection.createdAt))
            .put("plot", plotName).put("row", inspection.row ?: JSONObject.NULL)
            .put("lat", inspection.lat ?: JSONObject.NULL).put("lon", inspection.lon ?: JSONObject.NULL)
            .put("accuracy_m", inspection.accuracyM ?: JSONObject.NULL).put("note", inspection.note)
            .put("user_review", inspection.userReview).put("model_version", inspection.modelVersion)
            .put("model_sha256", inspection.modelSha256).put("prediction", prediction).put("photos", photos)
        val id = previous?.serverId ?: SyncIds.inspection(inspection.id)
        val request = JSONObject().put("id", id).put("base_version", version).put("updated_at", iso(updatedAt)).put("data", data)
        return SyncRecord(account, inspection.id, id, version, inspection.updatedAt, request.toString(), files.toString())
    }

    fun iso(value: Long): String = Instant.ofEpochMilli(value).toString()
    // Instant.parse on Android 13 rejects PostgreSQL's explicit +00:00 timestamp format.
    fun parseTime(value: String): Instant = java.time.OffsetDateTime.parse(value).toInstant()

    /** JSON objects have no member ordering; server serializers can change order and 1 vs 1.0. */
    fun sameJson(first: Any?, second: Any?): Boolean = when {
        first is JSONObject && second is JSONObject -> {
            val keys = first.keys().asSequence().toSet()
            keys == second.keys().asSequence().toSet() && keys.all { sameJson(first.opt(it), second.opt(it)) }
        }
        first is JSONArray && second is JSONArray -> first.length() == second.length() &&
            (0 until first.length()).all { sameJson(first.opt(it), second.opt(it)) }
        first is Number && second is Number -> first.toString().toBigDecimal().compareTo(second.toString().toBigDecimal()) == 0
        else -> first == second
    }
}
