package com.agronomy.phytomonitor

import android.graphics.Bitmap
import com.agronomy.phytomonitor.ml.LeafDetection
import com.agronomy.phytomonitor.ml.VerifierInput
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerifierInputTest {
    @Test fun preparesRgbNchwNormalizedCropsForDesktopParity() {
        // Deterministic image, two non-square crops exercise floor/ceil, padding and resize.
        val bitmap = Bitmap.createBitmap(531, 317, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(531 * 317) { index ->
            val x = index % 531
            val y = index / 531
            (0xff000000.toInt() or (((x * 7 + y * 3) % 256) shl 16) or
                (((x + y * 5) % 256) shl 8) or ((x * 2 + y) % 256))
        }
        bitmap.setPixels(pixels, 0, 531, 0, 0, 531, 317)
        try {
            val boxes = listOf(LeafDetection(0, 1f, 10.2f, 3.6f, 501.1f, 300.2f),
                LeafDetection(0, 1f, -5f, -10f, 49.7f, 97.3f))
            val output = File("build/reports/verifier-input").apply { mkdirs() }
            boxes.forEachIndexed { index, box ->
                val input = VerifierInput.prepare(bitmap, box, 224)
                val bytes = ByteArray(input.remaining())
                input.get(bytes)
                File(output, "input_$index.bin").writeBytes(bytes)
                input.rewind()
                val values = input.asFloatBuffer()
                assertEquals(3 * 224 * 224, values.remaining())
                while (values.hasRemaining()) {
                    val value = values.get()
                    assertTrue(value.isFinite() && value in -2.2f..2.7f)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
