package com.agronomy.phytomonitor.voice

import java.util.Locale

enum class VoiceAction { CAMERA, GALLERY, ANALYZE, MODEL, CROP, CANCEL }
data class VoiceCommand(val action: VoiceAction, val label: String, val value: String = "")

/** Exact fixed intents: no substring guessing and no action from partial speech. */
object VoiceCommands {
    private val aliases = linkedMapOf<String, VoiceCommand>()
    private fun add(command: VoiceCommand, vararg phrases: String) {
        phrases.forEach { aliases[it] = command }
    }
    init {
        add(VoiceCommand(VoiceAction.CAMERA,"Открываю камеру"),"сделать фото","сделай фото","сфотографируй","открой камеру")
        add(VoiceCommand(VoiceAction.GALLERY,"Открываю галерею"),"открыть галерею","открой галерею","из галереи")
        add(VoiceCommand(VoiceAction.ANALYZE,"Проверяю фото"),"проверить фото","проверь фото","проверь снимок","проверь ещё раз","проверить ещё раз")
        add(VoiceCommand(VoiceAction.MODEL,"Выбрана YOLO26n","yolo26n"),"быстрая модель","быструю модель","маленькая модель","выбери быструю модель")
        add(VoiceCommand(VoiceAction.MODEL,"Выбрана YOLO26s","yolo26s"),"большая модель","большую модель","выбери большую модель")
        add(VoiceCommand(VoiceAction.MODEL,"Выбрана YOLO11n","yolo11n"),"старая модель","старую модель","выбери старую модель")
        add(VoiceCommand(VoiceAction.CROP,"Все культуры","all"),"все культуры","покажи все культуры")
        add(VoiceCommand(VoiceAction.CROP,"Картофель","Potato"),"картофель","выбери картофель")
        add(VoiceCommand(VoiceAction.CROP,"Томат","Tomato"),"томат","томаты","выбери томаты")
        add(VoiceCommand(VoiceAction.CROP,"Огурец","Cucumber"),"огурец","огурцы","выбери огурцы")
        add(VoiceCommand(VoiceAction.CANCEL,"Команда отменена"),"стоп","отмена","отмени")
    }
    val phrases: List<String> get() = aliases.keys.toList()
    val grammar: List<String> get() = phrases + listOf("не делай фото","не открывай галерею","не проверяй фото","[unk]")
    fun parse(text: String): VoiceCommand? {
        val normalized = text.lowercase(Locale.ROOT).replace('ё','е')
            .replace(Regex("[.,!?;:]")," ").trim().replace(Regex("\\s+")," ")
        return aliases.entries.firstOrNull { it.key.replace('ё','е') == normalized }?.value
    }
}
