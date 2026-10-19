package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.actions.ActionDispatcher
import com.agronomy.phytomonitor.actions.ActionPort
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AppState
import com.agronomy.phytomonitor.actions.Operation
import com.agronomy.phytomonitor.voice.FreeSpeech
import com.agronomy.phytomonitor.voice.Understanding
import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Free Russian phrases: what is understood, what is refused, what is never turned into a command. */
class FreeSpeechTest {
    private fun actions(text: String, screen: String = "main"): List<Pair<VoiceAction, String>> {
        val understood = FreeSpeech.understand(text, screen, listOf("Теплица 2"))
        return (understood as? Understanding.Commands)?.commands.orEmpty().map { it.action to it.value }
    }

    @Test
    fun ordinaryRequestsMapToActions() {
        assertEquals(listOf(VoiceAction.OPEN_CAMERA to ""), actions("Включи, пожалуйста, камеру"))
        assertEquals(listOf(VoiceAction.SPEAK_RESULT to ""), actions("Расскажи, что показал результат"))
        assertEquals(listOf(VoiceAction.THRESHOLD to "35"), actions("Сделай порог тридцать пять процентов"))
        assertEquals(listOf(VoiceAction.CROP to "tomato"), actions("Сейчас смотрим помидоры"))
        assertEquals(listOf(VoiceAction.PHOTO_NUMBER to "21"), actions("Открой двадцать первый снимок", "gallery"))
        assertEquals(listOf(VoiceAction.SCROLL to "down"), actions("Пролистай чуть ниже", "journal"))
    }

    @Test
    fun connectorsMakeAPlan() {
        assertEquals(
            listOf(VoiceAction.SPEAK_RESULT to "", VoiceAction.SAVE_INSPECTION to ""),
            actions("Прочитай результат, а потом сохрани осмотр")
        )
        // «и» inside one request is not split when a half means nothing.
        assertEquals(listOf(VoiceAction.SHOW_BOXES to ""), actions("Покажи рамки и границы листьев"))
    }

    @Test
    fun negationNeverStartsASideEffect() {
        listOf("Не сохраняй пока осмотр", "Камеру не открывай", "Не выключай микрофон", "Пометь, что ответ не верный, а точнее не надо").forEach {
            assertTrue(it, FreeSpeech.understand(it, "main") !is Understanding.Commands || actions(it).none { (action, _) -> action == VoiceAction.SAVE_INSPECTION || action == VoiceAction.OPEN_CAMERA || action == VoiceAction.LISTEN_OFF })
        }
        assertEquals(listOf(VoiceAction.CROP to "tomato"), actions("Не картофель, а томат"))
        assertEquals(listOf(VoiceAction.SILENCE to ""), actions("Не читай дальше"))
    }

    @Test
    fun outOfScopeRequestsAreRefusedNotGuessed() {
        assertTrue(FreeSpeech.understand("Удали все снимки", "main") is Understanding.Refusal)
        assertTrue(FreeSpeech.understand("Какую дозу фунгицида взять", "main") is Understanding.Refusal)
        assertEquals(Understanding.NotUnderstood, FreeSpeech.understand("Какая завтра погода", "main"))
    }

    @Test
    fun shortCropNamesDoNotHideInsideOtherWords() {
        listOf("Сохранение сейчас не нужно", "Да, согласен", "Переключись на фасоль", "Соседний ряд").forEach {
            assertTrue(it, actions(it).none { (action, _) -> action == VoiceAction.CROP })
        }
        assertEquals(listOf(VoiceAction.CROP to "soybean"), actions("Выбери сою"))
    }

    @Test
    fun dictationIsNeverInterpreted() {
        assertEquals(Understanding.NotUnderstood, FreeSpeech.understand("Открой камеру и сохрани осмотр", "note"))
    }

    @Test
    fun aPlanFromOnePhraseRunsStepByStep() {
        val performed = mutableListOf<Operation>()
        val port = object : ActionPort {
            override fun state() = AppState(hasPhoto = true, hasResult = true)
            override fun perform(operation: Operation) {
                performed += operation
            }
            override fun tell(text: String, isError: Boolean, request: ActionRequest?) = Unit
        }
        val steps = (FreeSpeech.understand("Озвучь ответ и занеси осмотр в историю", "main") as Understanding.Commands).commands
        ActionDispatcher(port).submitPlan(steps.map { ActionRequest(it.action, it.value, ActionSource.VOICE) })
        assertEquals(listOf(Operation.SpeakResult, Operation.SaveInspection), performed)
    }
}
