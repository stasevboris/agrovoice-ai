package com.agronomy.phytomonitor.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import com.agronomy.phytomonitor.updates.UpdateRelease
import com.agronomy.phytomonitor.updates.VerifiedUpdateStore
import com.agronomy.phytomonitor.UserErrors

interface SyncApi {
    fun push(update: JSONObject): JSONObject
    fun upload(id: String, file: File, sha: String)
    fun pull(after: Long): JSONObject
    fun download(id: String, target: File, bytes: Long) { throw UnsupportedOperationException("Photo download is unavailable") }
}

class SyncHttpException(val status: Int) : IOException("HTTP $status")

/** One immutable origin/token pair per run; credentials never follow redirects. */
class SyncHttpClient(origin: String, private val token: String? = null,
                     private val active: () -> Boolean = { true }) : SyncApi {
    val origin = SyncEndpoint.normalize(origin)

    fun login(username: String, password: String): JSONObject = json("POST", "/auth/token",
        JSONObject().put("username", username).put("password", password))

    fun profile(): JSONObject = json("GET", "/me")

    fun updates(): List<UpdateRelease> = UpdateRelease.feed(request("GET", "/updates", null, "application/json") {}
        .toString(Charsets.UTF_8))

    /** Streams into the content-addressed store; size and SHA are checked there before the file appears. */
    fun downloadUpdate(release: UpdateRelease, store: VerifiedUpdateStore): File {
        val connection = open("GET", release.downloadPath)
        try {
            ensureActive()
            if (connection.responseCode !in 200..299) throw SyncHttpException(connection.responseCode)
            return store.stage(release, connection.inputStream) { active() && !Thread.currentThread().isInterrupted }
        } finally { connection.disconnect() }
    }

    override fun push(update: JSONObject): JSONObject = json("POST", "/sync/inspections",
        JSONObject().put("inspections", JSONArray().put(update))).getJSONArray("results").getJSONObject(0)

    override fun upload(id: String, file: File, sha: String) {
        java.util.UUID.fromString(id)
        request("PUT", "/photos/$id", file.length(), "application/octet-stream", sha) { connection ->
            connection.outputStream.use { output -> file.inputStream().use { input -> send(input, output) } }
        }
    }

    /** Checks the session between blocks: logout stops the body instead of waiting for the whole file. */
    private fun send(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    override fun pull(after: Long): JSONObject {
        require(after >= 0)
        return json("GET", "/sync/inspections?after=$after&limit=10")
    }

    override fun download(id: String, target: File, bytes: Long) {
        java.util.UUID.fromString(id)
        require(bytes in 1..SyncPayload.MAX_PHOTO_BYTES)
        val connection = open("GET", "/photos/$id")
        try {
            ensureActive()
            if (connection.responseCode !in 200..299) throw SyncHttpException(connection.responseCode)
            java.io.FileOutputStream(target).use { output ->
                var total = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > bytes) throw IOException("Photo exceeds declared size")
                        output.write(buffer, 0, count)
                    }
                }
                if (total != bytes) throw IOException("Incomplete photo")
                output.fd.sync()
            }
        } finally { connection.disconnect() }
    }

    private fun json(method: String, path: String, value: JSONObject? = null): JSONObject {
        val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
        return JSONObject(request(method, path, bytes?.size?.toLong(), "application/json") { connection ->
            if (bytes != null) connection.outputStream.use { it.write(bytes) }
        }.toString(Charsets.UTF_8))
    }

    private fun request(method: String, path: String, length: Long?, contentType: String,
                        sha: String? = null, write: (HttpURLConnection) -> Unit): ByteArray {
        val connection = open(method, path)
        try {
            if (sha != null) connection.setRequestProperty("X-Content-SHA256", sha)
            if (length != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(length)
                connection.setRequestProperty("Content-Type", contentType)
            }
            ensureActive()
            write(connection)
            ensureActive()
            val status = connection.responseCode
            if (status !in 200..299) throw SyncHttpException(status)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 16 * 1024 * 1024) throw IOException("Server response too large")
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(origin + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun ensureActive() {
        if (!active() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Sync transport session ended")
    }
}

fun syncError(error: Exception): String = if (UserErrors.noSpace(error)) UserErrors.NO_SPACE else when (error) {
    is SyncHttpException -> when (error.status) {
        401, 403 -> "Сеанс истёк или доступ запрещён. Нажмите «Войти» в настройках. Осмотр сохранён на устройстве."
        in 300..399 -> "Сервер перенаправляет запрос. Укажите конечный адрес сервера; токен не передан по перенаправлению."
        409 -> "Сервер обнаружил конфликт фото. Локальный осмотр сохранён; проверьте серверную запись."
        413 -> "Данные превышают лимит сервера. Осмотр сохранён на устройстве."
        422 -> "Сервер не принял формат осмотра. Локальные данные сохранены; обновите приложение или сервер."
        else -> "Сервер временно недоступен (HTTP ${error.status}). Осмотр сохранён; повторите отправку."
    }
    is IllegalArgumentException, is IllegalStateException -> error.message ?: "Осмотр не готов к передаче. Он сохранён на устройстве."
    else -> "Не удалось связаться с сервером. Осмотр сохранён на устройстве; отправка будет повторена."
}
