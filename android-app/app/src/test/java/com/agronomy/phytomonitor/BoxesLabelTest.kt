package com.agronomy.phytomonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxesLabelTest {
    @Test
    fun russianPluralForms() {
        assertEquals("0 рамок", boxesLabel(0))
        assertEquals("1 рамка", boxesLabel(1))
        assertEquals("3 рамки", boxesLabel(3))
        assertEquals("5 рамок", boxesLabel(5))
        assertEquals("11 рамок", boxesLabel(11))
        assertEquals("14 рамок", boxesLabel(14))
        assertEquals("21 рамка", boxesLabel(21))
        assertEquals("22 рамки", boxesLabel(22))
        assertEquals("111 рамок", boxesLabel(111))
    }
}
