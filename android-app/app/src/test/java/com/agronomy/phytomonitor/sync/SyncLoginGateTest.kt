package com.agronomy.phytomonitor.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SyncLoginGateTest {
    @Test fun delayedLoginCannotRestoreSessionOrScheduleAfterLogout() {
        val gate = SyncLoginGate()
        val active = AtomicBoolean(true)
        val loggedIn = AtomicBoolean(true)
        val scheduled = AtomicInteger(0)
        val response = CountDownLatch(1)
        val attempt = gate.begin()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                check(response.await(5, TimeUnit.SECONDS))
                gate.commit(attempt, active::get) { loggedIn.set(true); scheduled.incrementAndGet() }
            }
            gate.invalidate { loggedIn.set(false) }
            response.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertFalse(loggedIn.get())
            assertEquals(0, scheduled.get())
        } finally { response.countDown(); executor.shutdownNow() }
    }

    @Test fun disposedScreenCannotPublishCompletedAuthentication() {
        val gate = SyncLoginGate()
        val attempt = gate.begin()
        val screenActive = AtomicBoolean(true)
        screenActive.set(false)
        assertFalse(gate.commit(attempt, screenActive::get) { fail("Cancelled screen published session") })
    }

    @Test fun newerLoginWinsAndEachAttemptCanPublishOnlyOnce() {
        val gate = SyncLoginGate()
        val old = gate.begin()
        val newer = gate.begin()
        var current = "before"
        assertTrue(gate.commit(newer, { true }) { current = "newer" })
        assertFalse(gate.commit(old, { true }) { current = "old" })
        assertFalse(gate.commit(newer, { true }) { fail("Same attempt published twice") })
        assertEquals("newer", current)
    }
}
