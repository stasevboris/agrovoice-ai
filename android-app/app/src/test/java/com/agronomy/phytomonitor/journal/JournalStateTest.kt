package com.agronomy.phytomonitor.journal

import com.agronomy.phytomonitor.InspectionEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalStateTest {
    private val entries = listOf(
        InspectionEntry("one", 1, "photo1", "n", "Пятна", "incorrect", 1, "Теплица 2"),
        InspectionEntry("two", 2, "photo2", "s", "Без пятен", "correct", 1, "Поле")
    )

    @Test
    fun searchAndReviewApplyTogetherWithoutChangingSavedEntries() {
        val state = JournalState(entries, query = "  ТЕПЛИЦА  ", filter = "incorrect")
        assertEquals(listOf("one"), state.visible.map { it.id })
        assertEquals(emptyList<InspectionEntry>(), state.copy(filter = "correct").visible)
        assertEquals(entries, state.copy(query = "", filter = "all").visible)
    }
}
