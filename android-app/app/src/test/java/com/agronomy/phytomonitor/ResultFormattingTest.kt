package com.agronomy.phytomonitor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Display helpers of the redesigned screen: numbers are shown in the units the agronomist uses. */
class ResultFormattingTest {
    @Test
    fun inferenceTimeIsShownInSecondsWithOneDecimal() {
        assertEquals("1,1 с", formatSeconds(1136.0))
        assertEquals("0,5 с", formatSeconds(470.0))
        assertEquals("0,2 с", formatSeconds(170.4))
        assertEquals("12,0 с", formatSeconds(12000.0))
    }

    @Test
    fun thresholdIsShownInTheSameUnitAsTheVoiceCommands() {
        assertEquals("25 %", thresholdPercent(0.25f))
        assertEquals("48 %", thresholdPercent(0.48f))
        assertEquals("80 %", thresholdPercent(0.8f))
        assertEquals("29 %", thresholdPercent(0.29f))
    }

    @Test
    fun cropNamesComeFromOneListForTheScreenAndTheSheet() {
        assertEquals("Все культуры", cropName("all"))
        assertEquals("Картофель", cropName("Potato"))
        assertEquals("Томат", cropName("Tomato"))
        assertEquals("Огурец", cropName("Cucumber"))
        assertEquals("Unknown", cropName("Unknown"))
    }
}
