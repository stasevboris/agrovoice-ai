package com.agronomy.phytomonitor

import org.junit.Assert.*
import org.junit.Test

class PhotoQualityTest {
    private fun grey(value: Int): Int = (255 shl 24) or (value shl 16) or (value shl 8) or value

    private fun uniform(value: Int): PhotoQuality = analysePhoto(IntArray(64) { grey(value) }, 8, 8)

    @Test fun exposureTakesPriorityOverLowDetail() {
        val dark = uniform(0)
        assertEquals(PhotoVerdict.DARK, dark.verdict)
        assertEquals(0.0, dark.brightness, 0.0)
        val white = uniform(255)
        assertEquals(PhotoVerdict.BRIGHT, white.verdict)
        assertEquals(1.0, white.overexposedShare, 0.0)
    }

    @Test fun smoothMidtoneWarnsAboutDetailWithoutClaimingBlurOrDisease() {
        val result = uniform(128)
        assertEquals(PhotoVerdict.LOW_DETAIL, result.verdict)
        assertEquals(0.0, result.sharpness!!, 0.0)
        assertTrue(result.advice!!.contains("деталей"))
        assertFalse(result.advice!!.contains("размытый"))
    }

    @Test fun smoothingTexturedImageReducesSharpnessAndRaisesWarning() {
        val side = 32
        val texture = IntArray(side * side) { index ->
            grey(if ((index / side + index % side) % 2 == 0) 80 else 176)
        }
        // A box blur of alternating texture removes its contrast completely.
        val sharp = analysePhoto(texture, side, side)
        val blurred = uniform(128)
        assertEquals(PhotoVerdict.NO_WARNING, sharp.verdict)
        assertNull(sharp.advice)
        assertTrue(sharp.sharpness!! > blurred.sharpness!!)
        assertEquals(PhotoVerdict.LOW_DETAIL, blurred.verdict)
    }

    @Test fun clippedAreaWarnsEvenWhenAverageIsNotBright() {
        val pixels = IntArray(100) { grey(if (it < 30) 250 else 100) }
        val result = analysePhoto(pixels, 10, 10)
        assertEquals(145.0, result.brightness, 0.0)
        assertEquals(0.3, result.overexposedShare, 0.0)
        assertEquals(PhotoVerdict.BRIGHT, result.verdict)
    }

    @Test fun lumaUsesColorChannelsAndIgnoresAlpha() {
        val result = analysePhoto(IntArray(9) { 0x0000FF00 }, 3, 3)
        assertEquals(149.685, result.brightness, 1e-9)
    }

    @Test fun tinyAndNarrowPhotosHaveNoInventedSharpness() {
        for ((width, height) in listOf(1 to 1, 2 to 10, 10 to 2)) {
            val result = analysePhoto(IntArray(width * height) { grey(128) }, width, height)
            assertEquals(PhotoVerdict.NOT_ASSESSED, result.verdict)
            assertNull(result.sharpness)
            assertTrue(result.brightness.isFinite())
        }
    }

    @Test fun samplingPreservesAspectRatioWithoutUpscaling() {
        assertEquals(256 to 128, qualitySampleSize(2048, 1024))
        assertEquals(128 to 256, qualitySampleSize(1024, 2048))
        assertEquals(80 to 40, qualitySampleSize(80, 40))
        assertEquals(256 to 1, qualitySampleSize(4097, 1))
    }

    @Test fun invalidDimensionsAndOverflowAreRejected() {
        for ((width, height) in listOf(0 to 8, -1 to 8, 8 to 8, Int.MAX_VALUE to 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                analysePhoto(IntArray(9), width, height)
            }
        }
    }
}
