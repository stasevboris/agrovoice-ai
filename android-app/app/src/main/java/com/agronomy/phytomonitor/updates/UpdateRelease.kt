package com.agronomy.phytomonitor.updates

import org.json.JSONObject

data class UpdateRelease(val id: String, val kind: String, val version: String,
                         val fileName: String, val bytes: Long, val sha256: String,
                         val metadata: JSONObject) {
    val downloadPath: String get() = "/updates/$id/download"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("version", version)
        .put("file_name", fileName).put("bytes", bytes).put("sha256", sha256)
        .put("download_path", downloadPath).put("metadata", metadata)

    companion object {
        const val MAX_INDEX_BYTES = 1024 * 1024
        const val MAX_KB_BYTES = 5L * 1024 * 1024
        const val MAX_MODEL_BYTES = 512L * 1024 * 1024
        private val safeId = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")
        private val safeFile = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,159}")
        private val hash = Regex("[0-9a-f]{64}")

        fun parse(json: JSONObject): UpdateRelease {
            val id = json.getString("id")
            val kind = json.getString("kind")
            val version = json.getString("version")
            val file = json.getString("file_name")
            val bytes = json.getLong("bytes")
            val sha = json.getString("sha256")
            require(safeId.matches(id) && safeFile.matches(file) && hash.matches(sha)) { "Неверное имя или SHA обновления" }
            require(kind in setOf("model", "kb") && version.length in 1..128) { "Неизвестный тип обновления" }
            require(bytes in 1L..(if (kind == "kb") MAX_KB_BYTES else MAX_MODEL_BYTES)) { "Недопустимый размер обновления" }
            require(json.getString("download_path") == "/updates/$id/download") { "Ссылка обновления ведёт вне каталога сервера" }
            return UpdateRelease(id, kind, version, file, bytes, sha, json.getJSONObject("metadata"))
        }

        fun feed(text: String): List<UpdateRelease> {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_INDEX_BYTES) { "Каталог обновлений слишком большой" }
            val json = JSONObject(text)
            require(json.getInt("schema_version") == 1) { "Неизвестная версия каталога обновлений" }
            val array = json.getJSONArray("releases")
            require(array.length() <= 100) { "Слишком много обновлений" }
            val items = List(array.length()) { parse(array.getJSONObject(it)) }
            require(items.map { it.id }.distinct().size == items.size) { "Повторяющиеся обновления" }
            return items
        }
    }
}
