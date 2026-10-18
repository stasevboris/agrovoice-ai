package com.agronomy.phytomonitor.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The redesign separates three meanings that used to share one amber:
 * the marker on the photo, the condition of the leaf, and the confidence bar.
 */
class PaletteContrastTest {
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    @Test
    fun diseaseTextIsReadableOnLightAndDarkRows() {
        assertTrue("ржавый на белой строке", contrast(Rust40, Color.White) >= 4.5)
        assertTrue("ржавый на тёмной строке", contrast(Rust80, Color(0xFF0A0F0C)) >= 4.5)
    }

    @Test
    fun healthyTextIsReadableOnLightAndDarkRows() {
        assertTrue("зелёный на белой строке", contrast(Leaf40, Color.White) >= 4.5)
        assertTrue("зелёный на тёмной строке", contrast(Leaf80, Color(0xFF0A0F0C)) >= 4.5)
    }

    @Test
    fun conditionColourIsNotTheMarkerColour() {
        // Amber stays the marker only: a frame on the photo must never be read as «disease».
        assertTrue(Rust40 != DetectionAmber && Rust80 != DetectionAmber && Leaf40 != DetectionAmber)
    }
}
