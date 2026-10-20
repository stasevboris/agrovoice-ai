package com.agronomy.phytomonitor.updates

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONObject

/** Content-addressed files and a single atomic pointer retaining the last working version. */
class VerifiedUpdateStore(private val root: File) {
    init { check(root.isDirectory || root.mkdirs()) { "Не удалось открыть каталог обновлений" } }

    fun file(release: UpdateRelease): File {
        require(release.sha256.matches(Regex("[0-9a-f]{64}")) && release.kind in setOf("kb", "model"))
        return File(root, release.sha256 + if (release.kind == "kb") ".json" else ".onnx")
    }

    fun stage(release: UpdateRelease, input: InputStream, active: () -> Boolean = { true }): File {
        val part = File(root, UUID.randomUUID().toString() + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            input.use { source ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!active() || Thread.currentThread().isInterrupted) throw CancellationException("Сеанс обновления завершён")
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= release.bytes) { "Файл обновления больше объявленного размера" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    require(total == release.bytes && hex(digest.digest()) == release.sha256) { "Размер или SHA обновления не совпадает" }
                    output.fd.sync()
                }
            }
            if (!active()) throw CancellationException("Сеанс обновления завершён")
            Files.move(part.toPath(), file(release).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return file(release)
        } finally { part.delete() }
    }

    @Synchronized fun activate(release: UpdateRelease) {
        require(valid(release)) { "Обновление ещё не проверено или повреждено" }
        val previous = available(release.kind).firstOrNull()
        if (previous?.sha256 == release.sha256) return
        val value = JSONObject().put("current", release.toJson()).put("previous", previous?.toJson() ?: JSONObject.NULL)
        val part = File(root, UUID.randomUUID().toString() + ".part")
        try {
            FileOutputStream(part).use { output ->
                output.write(value.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(part.toPath(), pointer(release.kind).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
    }

    @Synchronized fun available(kind: String): List<UpdateRelease> = runCatching {
        val saved = pointer(kind)
        if (!saved.isFile || saved.length() > UpdateRelease.MAX_INDEX_BYTES) return emptyList()
        val value = JSONObject(saved.readText(Charsets.UTF_8))
        listOfNotNull(value.optJSONObject("current"), value.optJSONObject("previous"))
            .mapNotNull { runCatching { UpdateRelease.parse(it) }.getOrNull() }
            .filter { it.kind == kind && valid(it) }.distinctBy { it.sha256 }
    }.getOrDefault(emptyList())

    fun valid(release: UpdateRelease): Boolean = runCatching {
        val source = file(release)
        source.isFile && source.length() == release.bytes && sha256(source) == release.sha256
    }.getOrDefault(false)

    private fun pointer(kind: String): File {
        require(kind in setOf("kb", "model"))
        return File(root, "active-$kind.json")
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
