package com.agronomy.phytomonitor.voice

import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CancellationException

enum class InputKind { PHONE, WIRED, USB, BLUETOOTH }
data class InputDevice(val id: Int, val kind: InputKind)

/** Selection is a request; only AudioRecord.routedDevice can confirm the actual input. */
object InputRoutePolicy {
    /** Android's public MODE_NORMAL is zero; all communication/ringing modes belong to another use. */
    fun canAcquireMode(mode: Int): Boolean = mode == 0

    fun choose(devices: List<InputDevice>, bluetoothAllowed: Boolean): InputDevice? =
        devices.filter { bluetoothAllowed || it.kind != InputKind.BLUETOOTH }
            .minByOrNull { when (it.kind) {
                InputKind.WIRED -> 0
                InputKind.USB -> 1
                InputKind.BLUETOOTH -> 2
                InputKind.PHONE -> 3
            } }

    fun confirmed(requested: InputDevice, actual: InputDevice?): Boolean = actual != null &&
        requested.kind == actual.kind && (requested.kind == InputKind.BLUETOOTH ||
            requested.kind == InputKind.PHONE || requested.id == actual.id)
}

interface InputRoutingBackend : Closeable {
    val bluetoothAllowed: Boolean
    fun acquire(cancelled: () -> Boolean)
    fun devices(): List<InputDevice>
    fun prepare(device: InputDevice, cancelled: () -> Boolean): Boolean
    fun startRecorder(device: InputDevice): InputStream
    fun actualInput(): InputDevice?
    fun armMonitoring(device: InputDevice)
    fun resetRoute()
    fun stopCapture()
}

data class RoutedInput(val stream: InputStream, val device: InputDevice, val phoneFallback: Boolean)

class InputRouteSession(
    private val backend: InputRoutingBackend,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: (Long) -> Unit = Thread::sleep
) : Closeable {
    private var opened = false
    private var closed = false

    fun open(cancelled: () -> Boolean): RoutedInput {
        check(!opened && !closed) { "Audio session has already been used" }
        opened = true
        try {
            checkCancelled(cancelled)
            backend.acquire(cancelled)
            checkCancelled(cancelled)
            val devices = backend.devices()
            val requested = InputRoutePolicy.choose(devices, backend.bluetoothAllowed)
                ?: throw IOException("Нет доступного микрофона")
            val phone = devices.firstOrNull { it.kind == InputKind.PHONE }
            var stream: InputStream? = null
            var actual: InputDevice? = null
            if (backend.prepare(requested, cancelled)) {
                checkCancelled(cancelled)
                try {
                    stream = backend.startRecorder(requested)
                    actual = awaitInput(requested, cancelled)
                } catch (error: IOException) {
                    if (requested.kind == InputKind.PHONE) throw error
                } catch (error: UnsupportedOperationException) {
                    // AudioRecord.Builder reports unavailable/unsupported external inputs this way.
                    // CancellationException is intentionally not caught: cancellation never reopens a mic.
                    if (requested.kind == InputKind.PHONE) throw error
                }
            }
            if (actual == null && requested.kind != InputKind.PHONE && phone != null) {
                backend.resetRoute()
                checkCancelled(cancelled)
                stream = backend.startRecorder(phone)
                actual = awaitInput(phone, cancelled)
            }
            checkCancelled(cancelled)
            val confirmed = actual ?: throw IOException("Android не подтвердил вход микрофона. Проверь разрешения и повтори.")
            backend.armMonitoring(confirmed)
            return RoutedInput(requireNotNull(stream), confirmed, confirmed.kind == InputKind.PHONE)
        } catch (error: Exception) {
            try { close() } catch (closing: Exception) { error.addSuppressed(closing) }
            throw error
        }
    }

    private fun awaitInput(requested: InputDevice, cancelled: () -> Boolean): InputDevice? {
        val deadline = clock() + CONFIRM_TIMEOUT_MS
        while (clock() < deadline) {
            checkCancelled(cancelled)
            val actual = backend.actualInput()
            if (InputRoutePolicy.confirmed(requested, actual)) return actual
            waitMillis(20)
        }
        return null
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (closed || cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Listening cancelled")
    }

    fun stopCapture() = backend.stopCapture()
    override fun close() {
        if (closed) return
        closed = true
        backend.close()
    }

    companion object {
        const val CONFIRM_TIMEOUT_MS = 1500L
    }
}
