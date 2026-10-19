package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.photos.PreviewGuidance
import com.agronomy.phytomonitor.photos.previewVerdict
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PreviewGuidanceTest {
    @Test fun samplingAndSpeechHaveIndependentLimitsAndDoNotRepeatStableWarnings() {
        val guidance = PreviewGuidance()
        assertTrue(guidance.shouldSample(0))
        assertFalse(guidance.shouldSample(749))
        assertTrue(guidance.shouldSample(750))
        assertFalse(guidance.accept(PhotoVerdict.DARK, 0))
        assertTrue(guidance.accept(PhotoVerdict.DARK, 750))
        guidance.speechDelivered(PhotoVerdict.DARK, 750)
        assertFalse(guidance.accept(PhotoVerdict.DARK, 10000))
        assertFalse(guidance.accept(PhotoVerdict.BRIGHT, 11000))
        assertTrue(guidance.accept(PhotoVerdict.BRIGHT, 12000))
        guidance.speechDelivered(PhotoVerdict.BRIGHT, 12000)
        assertFalse(guidance.accept(PhotoVerdict.DARK, 13000))
        assertFalse(guidance.accept(PhotoVerdict.DARK, 14000))
        assertFalse(guidance.accept(PhotoVerdict.NO_WARNING, 21000))
        assertFalse(guidance.accept(PhotoVerdict.NO_WARNING, 22000))
        assertFalse(guidance.accept(PhotoVerdict.DARK, 23000))
        assertTrue(guidance.accept(PhotoVerdict.DARK, 24000))
    }

    @Test fun luminanceReadsOnlyTheCropWithPaddedStridesAndBufferOffset() {
        val buffer = ByteBuffer.allocate(210)
        for (i in 0 until buffer.limit()) buffer.put(i, 255.toByte())
        buffer.position(3)
        for (y in 2 until 6) for (x in 1 until 5) buffer.put(3 + y * 24 + x * 2, 20)
        assertEquals(PhotoVerdict.DARK, previewVerdict(buffer, 24, 2, 1, 2, 4, 4))
        assertEquals(3, buffer.position())
        assertEquals(PhotoVerdict.NOT_ASSESSED, previewVerdict(ByteBuffer.allocate(1), 1, 1, 0, 0, 1, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedFrameIsRejectedForTheCameraToReportUnavailable() {
        previewVerdict(ByteBuffer.allocate(4), 10, 1, 0, 0, 4, 4)
    }
}
