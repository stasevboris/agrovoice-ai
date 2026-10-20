package com.agronomy.phytomonitor.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechOutputTest {
    private class Backend : SpeechBackend {
        lateinit var ready: (String?) -> Unit
        val requests = mutableListOf<String>()
        val finishes = mutableListOf<(Boolean) -> Unit>()
        var stopped = false
        var closed = false
        override fun initialize(onReady: (String?) -> Unit) { ready = onReady }
        override fun speak(text: String, volume: Float, onFinished: (Boolean) -> Unit) {
            requests += text
            finishes += onFinished
        }
        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }

    private class Focus : SpeechFocus {
        var granted = true
        var held = false
        var lost: (() -> Unit)? = null
        override fun acquire(onLost: () -> Unit): Boolean {
            lost = onLost
            held = granted
            return granted
        }
        override fun release() { held = false }
    }

    @Test fun builtInVoiceWorksWithoutSystemRussianVoice() {
        val local = Backend()
        val system = Backend()
        val focus = Focus()
        val output = SpeechOutput(local, system, focus)
        local.ready("Denis")
        system.ready(null)
        assertTrue(output.available)
        assertEquals("Denis", output.voiceName)
        assertTrue(output.speak("Осмотр сохранён"))
        assertTrue(output.active)
        assertTrue(focus.held)
        assertEquals(listOf("Осмотр сохранён"), local.requests)
        assertTrue(system.requests.isEmpty())
        local.finishes.single()(true)
        assertFalse(output.active)
        assertFalse(focus.held)
        assertEquals("done", output.completion)
    }

    @Test fun missingOrFailedLocalVoiceUsesOfflineSystemVoice() {
        for (localAvailable in listOf(false, true)) {
            val local = Backend()
            val system = Backend()
            val output = SpeechOutput(local, system, Focus())
            local.ready(if (localAvailable) "Denis" else null)
            system.ready("Russian offline")
            assertTrue(output.speak("Результат"))
            if (localAvailable) local.finishes.single()(false)
            assertEquals(listOf("Результат"), system.requests)
            assertTrue(output.active)
            system.finishes.single()(true)
            assertEquals("done", output.completion)
        }
    }

    @Test fun missingBothVoicesAndDeniedFocusNeverStartPlayback() {
        val local = Backend()
        val system = Backend()
        val focus = Focus()
        val output = SpeechOutput(local, system, focus)
        local.ready(null)
        system.ready(null)
        assertTrue(output.initialized)
        assertFalse(output.speak("Результат"))
        assertTrue(output.status.contains("недоступен"))
        local.ready("Denis")
        focus.granted = false
        assertFalse(output.speak("Результат"))
        assertFalse(output.active)
        assertTrue(local.requests.isEmpty())
    }

    @Test fun cancellationAndFocusLossIgnoreLateNativeCompletionAndFallback() {
        for (loseFocus in listOf(false, true)) {
            val local = Backend()
            val system = Backend()
            val focus = Focus()
            val output = SpeechOutput(local, system, focus)
            local.ready("Denis")
            system.ready("Russian offline")
            output.speak("Старый результат")
            if (loseFocus) focus.lost!!() else output.stop()
            local.finishes.single()(false)
            assertFalse(output.active)
            assertFalse(focus.held)
            assertTrue(local.stopped)
            assertTrue(system.requests.isEmpty())
            output.speak("Новый результат")
            local.finishes.first()(true)
            assertTrue(output.active)
            local.finishes.last()(true)
            assertFalse(output.active)
        }
    }

    @Test fun closeRejectsLateInitializationAndNewRequests() {
        val local = Backend()
        val system = Backend()
        val output = SpeechOutput(local, system, Focus())
        output.close()
        local.ready("Denis")
        system.ready("Russian offline")
        assertFalse(output.available)
        assertFalse(output.speak("Результат"))
        assertTrue(local.closed)
        assertTrue(system.closed)
    }
}
