package com.agronomy.phytomonitor.ml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class SerialModelWorkTest {
    @Test
    fun closingDrainsAcceptedSaveBeforeReleasingNativeResources() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val work = SerialModelWork(onFailure = { throw it }, onClosed = { events += "closed" })
        work.submit {
            started.complete(Unit)
            release.await()
            events += "analysis"
        }
        work.submit { events += "save" }
        withTimeout(5000) { started.await() }
        work.close()
        assertTrue(events.isEmpty())
        assertThrows(IllegalStateException::class.java) { work.submit {} }
        release.complete(Unit)
        withTimeout(5000) { work.awaitClosed() }
        assertEquals(listOf("analysis", "save", "closed"), events.toList())
    }

    @Test
    fun aFailedOperationDoesNotDropTheNextAcceptedRequest() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val work = SerialModelWork(onFailure = { events += "error" }, onClosed = { events += "closed" })
        work.submit { error("broken photo") }
        work.submit { events += "next photo" }
        work.close()
        withTimeout(5000) { work.awaitClosed() }
        assertEquals(listOf("error", "next photo", "closed"), events.toList())
    }
}
