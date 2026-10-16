package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.RussianPhotoNumber
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
    @Test fun galleryNumbersAreExplicitAndConfirmIsSeparate() {
        assertEquals("3",VoiceCommands.parse("фото номер три")!!.value)
        assertEquals("21",VoiceCommands.parse("выбери фото номер двадцать один")!!.value)
        assertEquals("125",VoiceCommands.parse("номер сто двадцать пять")!!.value)
        assertEquals("2",VoiceCommands.parse("второе фото")!!.value)
        assertEquals(VoiceAction.FIRST_PHOTO,VoiceCommands.parse("первое фото")!!.action)
        assertEquals(VoiceAction.LAST_PHOTO,VoiceCommands.parse("последнее фото")!!.action)
        assertEquals(VoiceAction.CONFIRM,VoiceCommands.parse("подтвердить")!!.action)
        listOf("не подтверждать","не выбирай фото номер три","я видел три растения","фото ноль","один один","два двадцать","фото 0","удали фото три").forEach {assertNull(it,VoiceCommands.parse(it))}
        listOf(1,21,100,125,1000,1001,24001,999999).forEach {assertEquals(it,RussianPhotoNumber.parse(RussianPhotoNumber.words(it)))}
    }
}
