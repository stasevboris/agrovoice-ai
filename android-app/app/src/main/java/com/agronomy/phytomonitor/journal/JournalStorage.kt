package com.agronomy.phytomonitor.journal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** Leaves room for Room/WAL and errors; this reserve is a storage policy, not a measured model limit. */
object JournalStorage {
    const val RESERVE_BYTES = 8L * 1024 * 1024

    fun requireSpace(directory: File, bytes: Long, available: (File) -> Long = File::getUsableSpace) {
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать каталог импорта." }
        val free = available(directory)
        if (bytes < 0 || free < RESERVE_BYTES || bytes > free - RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места для импорта. Освободи память и повтори выбор архива.")
        }
    }

    /** A crash cannot leave a partial final photo. Existing files are never replaced. */
    fun copyPhoto(source: File, target: File, available: (File) -> Long = File::getUsableSpace) {
        val directory = requireNotNull(target.parentFile)
        requireSpace(directory, source.length(), available)
        val temporary = File.createTempFile("agrovoice_restore_", ".part", directory)
        try {
            FileOutputStream(temporary).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            // No REPLACE_EXISTING: a concurrent or older file must not be overwritten.
            Files.move(temporary.toPath(), target.toPath())
        } finally {
            temporary.delete()
        }
    }

    fun stageArchive(input: InputStream, target: File, available: (File) -> Long) {
        val directory = requireNotNull(target.parentFile)
        requireSpace(directory, 0, available)
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                requireSpace(directory, count.toLong(), available)
                output.write(buffer, 0, count)
            }
        }
    }
}
