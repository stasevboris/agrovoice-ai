package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.ui.LeafState
import com.agronomy.phytomonitor.ui.parseLeafLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class LeafLabelTest {
    @Test
    fun diseaseLabelIsSplitAndMarkedForAttention() {
        val label = parseLeafLabel("Томат: фитофтороз")
        assertEquals("Томат", label.crop)
        assertEquals("Фитофтороз", label.condition)
        assertEquals(LeafState.ATTENTION, label.state)
    }

    @Test
    fun healthyWordingsAreRecognised() {
        assertEquals(LeafState.HEALTHY, parseLeafLabel("Картофель: лист без указанной болезни").state)
        assertEquals(LeafState.HEALTHY, parseLeafLabel("Яблоня: здоровый").state)
        assertEquals(LeafState.HEALTHY, parseLeafLabel("Соя: лист").state)
    }

    @Test
    fun unknownConditionIsNeitherHealthyNorDisease() {
        assertEquals(LeafState.UNKNOWN, parseLeafLabel("Огурец: лист, состояние неизвестно").state)
        assertEquals(LeafState.UNKNOWN, parseLeafLabel("Лист").state)
    }
}
