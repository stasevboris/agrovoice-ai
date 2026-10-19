package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.RussianPhotoNumber
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandsTest {
    @Test fun cameraConfirmationAndImmediateCaptureAreDifferentIntents() {
        assertNull(VoiceCommands.parseForScreen("семь","main"))
        assertEquals("7",VoiceCommands.parseForScreen("семь","gallery")!!.value)
        assertNull(VoiceCommands.parseForScreen("подтвердить","main"))
        assertEquals(VoiceAction.CAMERA,VoiceCommands.parse("сделай фото")!!.action)
        assertEquals(VoiceAction.CAMERA_NOW,VoiceCommands.parse("сделай фото сразу")!!.action)
        assertEquals(VoiceAction.CONFIRM,VoiceCommands.parse("подтвердить")!!.action)
        assertEquals(VoiceAction.LISTEN_OFF,VoiceCommands.parse("стоп")!!.action)
        assertEquals(VoiceAction.LISTEN_ON,VoiceCommands.parse("слушай постоянно")!!.action)
        assertEquals(VoiceAction.BACK,VoiceCommands.parse("назад")!!.action)
    }
    @Test fun allModelsAndThresholdsHaveExplicitCommands() {
        VoiceCommands.modelIds.forEachIndexed {index,id->assertEquals(id,VoiceCommands.parse("модель ${RussianPhotoNumber.words(index+1)}")!!.value)}
        listOf(10,25,80).forEach {assertEquals(it.toString(),VoiceCommands.parse("порог ${RussianPhotoNumber.words(it)} процентов")!!.value)}
        listOf("порог девять","порог восемьдесят один","не выключай голос","не подтверждать","не сделай фото сразу").forEach {assertNull(it,VoiceCommands.parse(it))}
    }
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
        assertEquals("cucumber",VoiceCommands.parse("огурцы")!!.value)
        assertEquals("all",VoiceCommands.parse("все культуры")!!.value)
    }
    @Test fun fieldToolsAreExplicitAndRejectedOutsideMain() {
        val phrases=listOf("добавь заметку","открой журнал","сохрани осмотр","результат верный","результат неверный","не уверен","сравни модели","прочитай результат","спрячь рамки","покажи рамки")
        phrases.forEach {assertNotNull(it,VoiceCommands.parseForScreen(it,"main"));assertNull(it,VoiceCommands.parseForScreen(it,"camera"));assertNull(it,VoiceCommands.parseForScreen(it,"gallery"))}
        listOf("не сохраняй осмотр","не открывай журнал","не сравнивай модели","я думаю что результат верный").forEach {assertNull(it,VoiceCommands.parse(it))}
    }
    @Test fun galleryNumbersAreExplicitAndConfirmIsSeparate() {
        assertEquals("3",VoiceCommands.parse("фото номер три")!!.value)
        assertEquals("21",VoiceCommands.parse("выбери фото номер двадцать один")!!.value)
        assertEquals("125",VoiceCommands.parse("номер сто двадцать пять")!!.value)
        assertEquals("2",VoiceCommands.parse("второе фото")!!.value)
        assertEquals("4",VoiceCommands.parse("четвёртое фото")!!.value)
        assertEquals("4",VoiceCommands.parse("четвертое фото")!!.value)
        assertEquals(VoiceAction.FIRST_PHOTO,VoiceCommands.parse("первое фото")!!.action)
        assertEquals(VoiceAction.LAST_PHOTO,VoiceCommands.parse("последнее фото")!!.action)
        assertEquals(VoiceAction.CONFIRM,VoiceCommands.parse("подтвердить")!!.action)
        listOf("не подтверждать","не выбирай фото номер три","я видел три растения","фото ноль","один один","два двадцать","фото 0","удали фото три").forEach {assertNull(it,VoiceCommands.parse(it))}
        listOf(1,21,100,125,1000,1001,24001,999999).forEach {assertEquals(it,RussianPhotoNumber.parse(RussianPhotoNumber.words(it)))}
    }
}
