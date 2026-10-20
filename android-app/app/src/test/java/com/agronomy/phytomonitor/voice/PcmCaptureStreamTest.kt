package com.agronomy.phytomonitor.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PcmCaptureStreamTest {
    @Test
    fun stopUnblocksReadBeforeReleaseAndRepeatedCloseIsSafe() {
        val reading = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var releases = 0
        val source = object : PcmCapture {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(stopped.await(3, TimeUnit.SECONDS))
                return -3
            }
            override fun stop() { stopped.countDown() }
            override fun release() { releases++ }
        }
        val stream = PcmCaptureStream(source)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val read = worker.submit<Int> { stream.read(ByteArray(64)) }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            stream.stopCapture()
            assertEquals(-1, read.get(3, TimeUnit.SECONDS).toInt())
            assertEquals(0, releases)
            stream.close()
            stream.close()
            assertEquals(1, releases)
        } finally {
            stream.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun recorderFailureIsReportedOnceThenEndsWithoutAHotErrorLoop() {
        var reads = 0
        val source = object : PcmCapture {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return -6 }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val stream = PcmCaptureStream(source)
        assertThrows(IOException::class.java) { stream.read(ByteArray(32)) }
        assertEquals(-1, stream.read(ByteArray(32)))
        assertEquals(1, reads)
        stream.close()
    }

    @Test
    fun temporaryEmptyNonBlockingReadWaitsForPcmAndKeepsReadBounded() {
        var reads = 0
        val source = object : PcmCapture {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 6400)
                reads++
                if (reads == 1) return 0
                buffer[offset] = 7
                buffer[offset + 1] = 8
                return 2
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        PcmCaptureStream(source).use { stream ->
            val buffer = ByteArray(12800)
            assertEquals(2, stream.read(buffer))
            assertEquals(7.toByte(), buffer[0])
            assertEquals(8.toByte(), buffer[1])
        }
    }
}
