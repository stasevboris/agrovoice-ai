package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandsTest {
    @Test fun unrelatedAndNegatedSpeechDoesNotTriggerCamera() {
        listOf("не делай фото","я уже сделал фото вчера","не открывай галерею","сделай фото и удали его","[unk] сделать фото","").forEach {
            assertNull(it,VoiceCommands.parse(it))
        }
    }
    @Test fun normalizedRepeatUsesSameIntentAndChoicesAreExplicit() {
        assertEquals(VoiceAction.ANALYZE,VoiceCommands.parse("  ПРОВЕРЬ   ЕЩЕ РАЗ! ")!!.action)
        assertEquals("yolo26n",VoiceCommands.parse("быстрая модель")!!.value)
        assertEquals("yolo26s",VoiceCommands.parse("большая модель")!!.value)
        assertEquals("yolo11n",VoiceCommands.parse("старую модель")!!.value)
        assertEquals("Cucumber",VoiceCommands.parse("огурцы")!!.value)
        assertEquals("all",VoiceCommands.parse("все культуры")!!.value)
    }
}
