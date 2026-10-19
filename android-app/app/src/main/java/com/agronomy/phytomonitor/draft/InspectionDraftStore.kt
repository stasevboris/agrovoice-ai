package com.agronomy.phytomonitor.draft

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Coordinate all ViewModel lifetimes for one file; do disk work on a background worker. */
class InspectionDraftStore private constructor(private val file: File, private val generation: Long?) {
    constructor(file: File) : this(file, null)

    private val ownership = owners.getOrPut(file.absoluteFile.normalize().path) { Ownership() }
    private val pending = File(file.path + ".pending")

    /** Claim once when a new ViewModel is created. No disk I/O or waiting for an older fsync. */
    fun writer(): InspectionDraftStore = InspectionDraftStore(file, ownership.generation.incrementAndGet())

    /** Includes journal commit and snapshot clear: a new owner must read after this entire operation. */
    fun <T> withOwnership(operation: () -> T): T = synchronized(ownership) {
        if (generation != null && generation != ownership.generation.get()) throw StaleDraftWriterException()
        operation()
    }

    fun read(): InspectionDraft? = withOwnership {
        if (!file.exists()) return@withOwnership null
        file.inputStream().use { input ->
            require(input.channel.size() <= MAX_BYTES) { "Черновик слишком большой" }
            val bytes = input.readBytes()
            require(bytes.size <= MAX_BYTES) { "Черновик слишком большой" }
            InspectionDraft.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
        }
    }

    fun write(draft: InspectionDraft) = withOwnership {
        val bytes = draft.toJson().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Черновик слишком большой" }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Не удалось открыть каталог черновика" }
        FileOutputStream(pending).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // Unlike File.renameTo / AtomicFile.finishWrite, a failed replace throws to the caller.
        Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        Unit
    }

    /** An old completion may only clear the snapshot it belongs to. Photos and reports are untouched. */
    fun clear(id: String): Boolean = withOwnership {
        if (read()?.id != id) return@withOwnership false
        if (!file.delete()) throw IOException("Не удалось удалить черновик")
        true
    }

    /** Explicit user recovery. The original bytes are kept under a unique name, never deleted. */
    fun preserveDamaged(): File? = withOwnership {
        if (!file.exists()) return@withOwnership null
        val recovery = File(file.parentFile, "${file.name}.recovery-${UUID.randomUUID()}")
        Files.move(file.toPath(), recovery.toPath(), ATOMIC_MOVE)
        recovery
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val owners = ConcurrentHashMap<String, Ownership>()
    }

    private class Ownership {
        val generation = AtomicLong()
    }
}

class StaleDraftWriterException : IllegalStateException("Черновик уже открыт в другом окне приложения")
