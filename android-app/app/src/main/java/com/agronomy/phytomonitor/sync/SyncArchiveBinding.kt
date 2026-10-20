package com.agronomy.phytomonitor.sync

import com.agronomy.phytomonitor.journal.InspectionWithDetails
import com.agronomy.phytomonitor.journal.MAX_INSPECTION_FRAMES
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Portable provenance is untrusted input, not permission to overwrite a server version.
 * Restore IDs only for the original account and namespaced local identity. The first request
 * uses base_version=0: exact data/time are idempotent; any differing existing record conflicts.
 * The server response, not the archive, supplies the subsequently writable serverVersion.
 */
internal object SyncArchiveBinding {
    fun recover(account: String, details: InspectionWithDetails): SyncRecord? {
        val source = JSONObject(details.inspection.predictionJson).optJSONObject("agrovoice_sync_source") ?: return null
        if (source.optString("account") != account) return null
        val separator = account.lastIndexOf('|')
        require(separator > 0 && separator < account.lastIndex &&
            SyncEndpoint.normalize(account.substring(0, separator)) == account.substring(0, separator)) {
            "Серверная привязка архива имеет неверный адрес учётной записи."
        }
        val remote = source.getJSONObject("record")
        val serverId = UUID.fromString(remote.getString("id")).toString()
        require(details.inspection.id == SyncIds.importedInspection(account, serverId)) {
            "ID восстановленного осмотра не соответствует его происхождению. Отправка остановлена; локальные данные сохранены."
        }
        require(remote.getLong("version") > 0) { "В архиве нет корректной исходной серверной версии." }
        val data = remote.getJSONObject("data")
        val references = data.getJSONArray("photos")
        require(references.length() in 1..MAX_INSPECTION_FRAMES && details.photos.size == references.length()) {
            "Серверная привязка серии требует резервной копии всех кадров."
        }
        val files = JSONArray()
        val ids = mutableSetOf<String>()
        for (index in 0 until references.length()) {
            val reference = references.getJSONObject(index)
            val photoId = UUID.fromString(reference.getString("id")).toString()
            require(ids.add(photoId)) { "Повторяющиеся серверные ID кадров в архиве." }
            val expected = SyncIds.importedPhoto(account, serverId, photoId)
            // Compatibility with the single-frame importer shipped before archive v3.
            val photo = details.photos.firstOrNull { it.photo.id == expected }?.photo
                ?: details.photos.singleOrNull()?.photo?.takeIf { it.id in setOf(details.inspection.id, "${details.inspection.id}-00") }
                ?: error("ID кадра архива не соответствует его серверному происхождению.")
            val sha = reference.getString("sha256")
            require(Regex("[0-9a-f]{64}").matches(sha) && photo.sha256 == sha) {
                "Фото архива отличается от исходной серверной ссылки. Отправка остановлена; осмотр сохранён."
            }
            val bytes = reference.getLong("bytes")
            require(bytes in 1..SyncPayload.MAX_PHOTO_BYTES) { "Неверный размер фото в серверной привязке архива." }
            files.put(JSONObject().put("id", photoId).put("frame_id", photo.id).put("path", File(photo.localPath).canonicalPath)
                .put("sha256", sha).put("bytes", bytes))
        }
        val time = remote.getString("updated_at")
        val request = JSONObject().put("id", serverId).put("base_version", 0).put("updated_at", time)
            .put("data", JSONObject(data.toString()))
        return SyncRecord(account, details.inspection.id, serverId, serverVersion = 0,
            snapshotUpdatedAt = SyncPayload.parseTime(time).toEpochMilli(), request = request.toString(), photoFiles = files.toString(),
            state = "synced", expertStatus = "unreviewed")
    }
}
