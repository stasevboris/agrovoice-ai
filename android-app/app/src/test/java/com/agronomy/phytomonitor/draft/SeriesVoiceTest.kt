package com.agronomy.phytomonitor.draft

import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class SeriesVoiceTest {
    @Test
    fun frameSelectionIsDistinctFromGallerySelectionAndWorksInJournal() {
        val main = VoiceCommands.parseForScreen("выбери кадр два", "main")
        val saved = VoiceCommands.parseForScreen("кадр два", "journal")
        assertNotNull(main)
        assertNotNull(saved)
        assertEquals("SELECT_FRAME", main!!.action.name)
        assertEquals("2", main.value)
        assertEquals(main.action, saved!!.action)
        assertNull(VoiceCommands.parseForScreen("не выбирай кадр два", "main"))
        assertNull(VoiceCommands.parseForScreen("кадр два", "note"))
    }

    @Test
    fun addingAndAnglesHaveExplicitVoiceCommands() {
        assertEquals("ADD_FRAME", VoiceCommands.parse("добавь кадр")?.action?.name)
        assertEquals("ADD_FRAME_GALLERY", VoiceCommands.parse("добавь кадр из галереи")?.action?.name)
        assertEquals("back", VoiceCommands.parse("это оборот листа")?.value)
        assertEquals("front", VoiceCommands.parse("это лицевая сторона")?.value)
    }
}
