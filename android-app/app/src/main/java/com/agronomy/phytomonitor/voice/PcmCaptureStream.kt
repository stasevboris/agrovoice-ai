package com.agronomy.phytomonitor.voice

import java.io.InputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

interface PcmCapture {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
    fun release()
}

class PcmCaptureStream(private val capture: PcmCapture) : InputStream() {
    private val stopped = AtomicBoolean()
    private val stopIssued = AtomicBoolean()
    private val released = AtomicBoolean()
    private val pair = ByteArray(2)
    private var secondByte = -1

    override fun read(): Int {
        if (stopped.get()) return -1
        if (secondByte >= 0) return secondByte.also { secondByte = -1 }
        val size = read(pair, 0, 2)
        if (size < 0) return -1
        if (size > 1) secondByte = pair[1].toInt() and 255
        return pair[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (stopped.get()) return -1
        if (secondByte >= 0) {
            buffer[offset] = secondByte.toByte()
            secondByte = -1
            return 1
        }
        if (length == 1) {
            val value = read()
            if (value < 0) return -1
            buffer[offset] = value.toByte()
            return 1
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        var size: Int
        do {
            if (stopped.get() || Thread.currentThread().isInterrupted) return -1
            size = capture.read(buffer, offset, minOf(length, 6400) and -2)
            if (size == 0) {
                if (System.nanoTime() >= deadline) {
                    stopped.set(true)
                    throw IOException("Микрофон не передаёт данные. Повтори команду.")
                }
                try { Thread.sleep(10) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
        } while (size == 0)
        if (stopped.get()) return -1
        if (size < 0) {
            stopped.set(true)
            throw IOException("Не удалось прочитать микрофон (код $size)")
        }
        return size
    }

    /** Stop the native read before joining Vosk's thread. Release its native object only after join. */
    fun stopCapture() {
        stopped.set(true)
        if (stopIssued.compareAndSet(false, true)) capture.stop()
    }

    override fun close() {
        try { stopCapture() } finally {
            if (released.compareAndSet(false, true)) capture.release()
        }
    }
}
