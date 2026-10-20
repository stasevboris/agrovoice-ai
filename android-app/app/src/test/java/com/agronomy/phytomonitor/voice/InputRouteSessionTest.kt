package com.agronomy.phytomonitor.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class InputRouteSessionTest {
    private val phone = InputDevice(1, InputKind.PHONE)
    private val headset = InputDevice(2, InputKind.BLUETOOTH)

    @Test
    fun unconfirmedHeadsetTimesOutThenConfirmsActualPhoneInput() {
        val backend = FakeBackend(listOf(phone, headset), actual = { phone })
        var now = 0L
        val session = InputRouteSession(backend, { now }, { now += it })
        val opened = session.open { false }
        assertEquals(phone, opened.device)
        assertTrue(opened.phoneFallback)
        assertEquals(listOf(headset, phone), backend.requests)
        assertTrue(now >= InputRouteSession.CONFIRM_TIMEOUT_MS)
        assertEquals(1, backend.resets)
        session.close()
        session.close()
        assertEquals(1, backend.closes)
    }

    @Test
    fun cancellationWhileWaitingReleasesSessionAndNeverReportsHeadset() {
        var now = 0L
        val backend = FakeBackend(listOf(phone, headset), actual = { null })
        val session = InputRouteSession(backend, { now }, { now += it })
        assertThrows(CancellationException::class.java) { session.open { now >= 80 } }
        assertEquals(1, backend.closes)
        assertFalse(backend.armed)
    }

    @Test
    fun permissionFailureOrRecorderFailureReleasesResources() {
        val backend = FakeBackend(listOf(phone), actual = { phone }).apply { recorderFailure = true }
        assertThrows(IOException::class.java) { InputRouteSession(backend).open { false } }
        assertEquals(1, backend.closes)
        assertFalse(backend.armed)
    }

    @Test
    fun repeatedSessionsDoNotReuseClosedRecorderOrOldRoute() {
        val first = FakeBackend(listOf(phone, headset), actual = { headset })
        val firstSession = InputRouteSession(first)
        assertEquals(headset, firstSession.open { false }.device)
        firstSession.close()
        assertThrows(IllegalStateException::class.java) { firstSession.open { false } }
        val second = FakeBackend(listOf(phone), actual = { phone })
        val secondSession = InputRouteSession(second)
        assertEquals(phone, secondSession.open { false }.device)
        secondSession.close()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test
    fun unsupportedHeadsetRecorderFallsBackToConfirmedPhoneInput() {
        val backend = FakeBackend(listOf(phone, headset), actual = { phone }).apply {
            headsetFailure = UnsupportedOperationException("AudioRecord uninitialized")
        }
        InputRouteSession(backend).use { session ->
            assertEquals(phone, session.open { false }.device)
            assertEquals(listOf(headset, phone), backend.requests)
            assertEquals(1, backend.resets)
        }
        assertEquals(1, backend.closes)
    }

    @Test
    fun cancellationFromHeadsetRecorderNeverOpensFallbackMicrophone() {
        val backend = FakeBackend(listOf(phone, headset), actual = { phone }).apply {
            headsetFailure = CancellationException("cancelled")
        }
        assertThrows(CancellationException::class.java) { InputRouteSession(backend).open { false } }
        assertEquals(listOf(headset), backend.requests)
        assertEquals(0, backend.resets)
        assertEquals(1, backend.closes)
    }

    private class FakeBackend(private val available: List<InputDevice>, private val actual: () -> InputDevice?) : InputRoutingBackend {
        var closes = 0
        var resets = 0
        var armed = false
        var recorderFailure = false
        var headsetFailure: RuntimeException? = null
        val requests = mutableListOf<InputDevice>()
        override val bluetoothAllowed = true
        override fun acquire(cancelled: () -> Boolean) = Unit
        override fun devices() = available
        override fun prepare(device: InputDevice, cancelled: () -> Boolean) = true
        override fun startRecorder(device: InputDevice): InputStream {
            requests += device
            if (device.kind == InputKind.BLUETOOTH) headsetFailure?.let { throw it }
            if (recorderFailure) throw IOException("microphone denied")
            return ByteArrayInputStream(byteArrayOf())
        }
        override fun actualInput() = actual()
        override fun armMonitoring(device: InputDevice) { armed = true }
        override fun resetRoute() { resets++ }
        override fun stopCapture() = Unit
        override fun close() { closes++; armed = false }
    }
}
