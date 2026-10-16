package com.agronomy.phytomonitor.voice

import java.util.Locale

enum class VoiceAction { CAMERA, OPEN_CAMERA, GALLERY, ANALYZE, MODEL, CROP, CANCEL, PHOTO_NUMBER, FIRST_PHOTO, LAST_PHOTO, NEXT_PHOTO, PREVIOUS_PHOTO, CONFIRM }
data class VoiceCommand(val action: VoiceAction, val label: String, val value: String = "")

/** Exact fixed intents: no substring guessing and no action from partial speech. */
object VoiceCommands {
    private val aliases = linkedMapOf<String, VoiceCommand>()
    private fun add(command: VoiceCommand, vararg phrases: String) {
        phrases.forEach { aliases[it] = command }
    }
    init {
        add(VoiceCommand(VoiceAction.CAMERA,"Делаю фото"),"сделать фото","сделай фото","сфотографируй")
        add(VoiceCommand(VoiceAction.OPEN_CAMERA,"Открываю камеру"),"открой камеру")
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
        add(VoiceCommand(VoiceAction.CONFIRM,"Фото подтверждено"),"подтвердить","подтверди","выбрать фото","выбери это фото")
        add(VoiceCommand(VoiceAction.FIRST_PHOTO,"Первое фото"),"первое фото","самое новое фото","новое фото")
        add(VoiceCommand(VoiceAction.LAST_PHOTO,"Последнее фото"),"последнее фото","самое старое фото","старое фото")
        add(VoiceCommand(VoiceAction.NEXT_PHOTO,"Следующее фото"),"следующее фото","дальше")
        add(VoiceCommand(VoiceAction.PREVIOUS_PHOTO,"Предыдущее фото"),"предыдущее фото","назад")
        listOf("второе","третье","четвёртое","пятое","шестое","седьмое","восьмое","девятое","десятое").forEachIndexed {index,word ->
            add(VoiceCommand(VoiceAction.PHOTO_NUMBER,"Фото №${index+2}",(index+2).toString()),"$word фото")
        }
    }
    val phrases: List<String> get() = aliases.keys.toList()
    val grammar: List<String> get() = aliases.filterValues {it.action in setOf(VoiceAction.CAMERA,VoiceAction.OPEN_CAMERA,VoiceAction.GALLERY,VoiceAction.ANALYZE,VoiceAction.MODEL,VoiceAction.CROP,VoiceAction.CANCEL)}.keys.toList() + listOf("не делай фото","не открывай галерею","не проверяй фото","[unk]")
    fun galleryGrammar(count: Int): List<String>? {
        if(count>10000)return null // Use the full Russian vocabulary for unusually large libraries.
        val galleryActions=setOf(VoiceAction.CONFIRM,VoiceAction.CANCEL,VoiceAction.FIRST_PHOTO,VoiceAction.LAST_PHOTO,VoiceAction.NEXT_PHOTO,VoiceAction.PREVIOUS_PHOTO,VoiceAction.PHOTO_NUMBER)
        return aliases.filterValues {it.action in galleryActions}.keys.toList() +
            listOf("фото номер","выбери фото номер","номер","фото") +
            (1..count.coerceIn(1,10000)).map(RussianPhotoNumber::words) +
            listOf("не подтверждать","не выбирай фото","сегодня хорошая погода","[unk]")
    }
    fun parse(text: String): VoiceCommand? {
        val normalized = text.lowercase(Locale.ROOT).replace('ё','е')
            .replace(Regex("[.,!?;:]")," ").trim().replace(Regex("\\s+")," ")
        aliases.entries.firstOrNull { it.key.replace('ё','е') == normalized }?.value?.let {return it}
        val number=RussianPhotoNumber.parse(normalized) ?: return null
        return VoiceCommand(VoiceAction.PHOTO_NUMBER,"Фото №$number",number.toString())
    }
}

/** Cardinal numbers and explicit photo prefixes, never a number hidden in an unrelated sentence. */
object RussianPhotoNumber {
    private val small=listOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val tens=listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val hundreds=listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private fun belowThousand(number: Int): String = listOf(hundreds[number/100],if(number%100<20)small[number%100] else listOf(tens[number%100/10],small[number%10]).filter {it.isNotEmpty()}.joinToString(" ")).filter {it.isNotEmpty()}.joinToString(" ")
    fun words(number: Int): String {
        require(number in 1..999999)
        if(number<1000)return belowThousand(number)
        val group=number/1000
        val prefix=belowThousand(group).split(" ").map {when(it){"один"->"одна";"два"->"две";else->it}}.joinToString(" ")
        val suffix=when {group%100 in 11..19->"тысяч";group%10==1->"тысяча";group%10 in 2..4->"тысячи";else->"тысяч"}
        return listOf(prefix,suffix,belowThousand(number%1000)).filter {it.isNotEmpty()}.joinToString(" ")
    }
    fun parse(text: String): Int? {
        val body=text.removePrefix("выбери ").removePrefix("фото ").removePrefix("номер ").trim()
        if(body.matches(Regex("[0-9]{1,6}")))return body.toInt().takeIf {it>0}
        val ordinal=mapOf("первое" to 1,"второе" to 2,"третье" to 3,"четвертое" to 4,"пятое" to 5,"шестое" to 6,"седьмое" to 7,"восьмое" to 8,"девятое" to 9,"десятое" to 10)
        ordinal[body.removeSuffix(" фото")]?.let {return it}
        val values=small.withIndex().filter {it.index>0}.associate {it.value to it.index} +
            tens.withIndex().filter {it.index>=2}.associate {it.value to it.index*10} +
            hundreds.withIndex().filter {it.index>0}.associate {it.value to it.index*100} + mapOf("одна" to 1,"две" to 2)
        var part=0;var total=0
        for(word in body.split(" ")) {
            if(word in setOf("тысяча","тысячи","тысяч")){if(total!=0)return null;total=(if(part==0)1 else part)*1000;part=0}
            else part+=values[word] ?: return null
        }
        val number=total+part
        if(number !in 1..999999)return null
        return number.takeIf {words(it)==body || it==1000 && body=="тысяча"}
    }
}
