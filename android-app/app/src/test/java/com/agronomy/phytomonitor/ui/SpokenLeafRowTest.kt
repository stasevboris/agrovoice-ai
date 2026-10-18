package com.agronomy.phytomonitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What TalkBack reads for a result row: one sentence per leaf, the number and the crop in words. */
class SpokenLeafRowTest {
    @Test
    fun diseaseRowNamesCropConditionAndConfidenceInOneSentence() {
        assertEquals(
            "Лист 1: томат, мозаика, уверенность модели 66 процентов",
            spokenLeafRow(1, "Томат: мозаика", 0.66f)
        )
    }

    @Test
    fun healthyRowDoesNotRepeatTheCropWhenTheConditionIsOnlyTheCrop() {
        assertEquals(
            "Лист 3: огурец, лист, уверенность модели 71 процентов",
            spokenLeafRow(3, "Огурец: Лист", 0.71f)
        )
        assertEquals(
            "Лист 2: соя, здоровый, уверенность модели 90 процентов",
            spokenLeafRow(2, "Соя: здоровый", 0.9f)
        )
    }

    @Test
    fun confidenceIsRoundedToWholePercentsAndClamped() {
        assertEquals("Лист 1: яблоня, парша, уверенность модели 0 процентов", spokenLeafRow(1, "Яблоня: парша", -0.2f))
        assertEquals("Лист 1: яблоня, парша, уверенность модели 100 процентов", spokenLeafRow(1, "Яблоня: парша", 1.4f))
    }
}
