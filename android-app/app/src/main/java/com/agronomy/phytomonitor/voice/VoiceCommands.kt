package com.agronomy.phytomonitor.voice

import com.agronomy.phytomonitor.ml.CropVocabulary
import java.util.Locale

enum class VoiceAction {
    CAMERA, CAMERA_NOW, OPEN_CAMERA, GALLERY, SYSTEM_GALLERY, ANALYZE, MODEL, CROP,
    CANCEL, BACK, EXIT, HELP, CLOSE_HELP, LISTEN_ON, LISTEN_OFF, THRESHOLD, THRESHOLD_UP,
    THRESHOLD_DOWN, SCROLL, PHOTO_NUMBER, FIRST_PHOTO, LAST_PHOTO, NEXT_PHOTO, PREVIOUS_PHOTO,
    CONFIRM, NOTE, CLEAR_NOTE, JOURNAL, JOURNAL_NUMBER, SAVE_INSPECTION, ASSESS, COMPARE,
    SPEAK_RESULT, SILENCE, SHOW_BOXES, HIDE_BOXES, ROW, PLOT, PLOTS, CREATE_PLOT, RENAME_PLOT,
    DELETE_PLOT, SETTINGS, SET_SETTING, SEARCH, JOURNAL_FILTER, EXPORT_JOURNAL, IMPORT_JOURNAL,
    KNOWLEDGE, READ_KNOWLEDGE, KNOWLEDGE_NUMBER, KNOWLEDGE_NEXT, KNOWLEDGE_PREVIOUS, LEAF_KNOWLEDGE,
    PLANT, PLANT_HISTORY, COMPARE_INSPECTIONS, CAMERA_GUIDANCE
}
data class VoiceCommand(val action: VoiceAction, val label: String, val value: String = "")

/** Exact fixed intents: no substring guessing and no action from partial speech. */
object VoiceCommands {
    val modelIds=listOf("yolo11n","yolo26n","mobilenet_v3_large","efficientnet_b0","masked_mobilenet_v3_large","distilled_mobilenet_v3_large","yolo26s","deimv2_n","yolo26m")
    private val aliases = linkedMapOf<String, VoiceCommand>()
    private fun add(command: VoiceCommand, vararg phrases: String) {
        phrases.forEach { aliases[it] = command }
    }
    init {
        add(VoiceCommand(VoiceAction.CAMERA_GUIDANCE, "Подсказки кадра включены", "true"), "включи подсказки кадра")
        add(VoiceCommand(VoiceAction.CAMERA_GUIDANCE, "Подсказки кадра выключены", "false"), "выключи подсказки кадра")
        add(VoiceCommand(VoiceAction.PLANT_HISTORY, "История растения"), "история растения", "открой историю растения", "покажи историю растения")
        add(VoiceCommand(VoiceAction.PLANT, "Без растения", ""), "убери растение", "без растения")
        add(VoiceCommand(VoiceAction.NOTE,"Заметка"),"добавь заметку","запиши заметку","измени заметку","исправь заметку")
        add(VoiceCommand(VoiceAction.CLEAR_NOTE,"Очистить текст заметки"),"очисти текст заметки","очисти заметку")
        add(VoiceCommand(VoiceAction.JOURNAL,"Журнал осмотров"),"открой журнал","покажи историю")
        add(VoiceCommand(VoiceAction.PLOTS,"Участки"),"открой участки","покажи участки")
        add(VoiceCommand(VoiceAction.SAVE_INSPECTION,"Сохраняю осмотр"),"сохрани осмотр","сохранить осмотр")
        add(VoiceCommand(VoiceAction.ASSESS,"Оценка: верно","correct"),"результат верный","всё верно")
        add(VoiceCommand(VoiceAction.ASSESS,"Оценка: неверно","incorrect"),"результат неверный","ошибка модели")
        add(VoiceCommand(VoiceAction.ASSESS,"Оценка: не уверен","uncertain"),"не уверен","нужна проверка")
        add(VoiceCommand(VoiceAction.COMPARE,"Сравниваю модели"),"сравни модели","сравнить модели")
        add(VoiceCommand(VoiceAction.SPEAK_RESULT,"Читаю результат"),"прочитай результат","озвучь результат")
        add(VoiceCommand(VoiceAction.SILENCE,"Озвучивание остановлено"),"замолчи","останови озвучивание")
        add(VoiceCommand(VoiceAction.SETTINGS,"Настройки приложения"),"открой настройки","настройки приложения")
        add(VoiceCommand(VoiceAction.KNOWLEDGE,"Справочник"),"открой справочник","справочник")
        add(VoiceCommand(VoiceAction.READ_KNOWLEDGE,"Чтение справки"),"прочитай справку","прочитай карточку","чем лечить")
        add(VoiceCommand(VoiceAction.KNOWLEDGE_NEXT,"Следующая карточка"),"следующая карточка","следующую карточку")
        add(VoiceCommand(VoiceAction.KNOWLEDGE_PREVIOUS,"Предыдущая карточка"),"предыдущая карточка","предыдущую карточку")
        add(VoiceCommand(VoiceAction.SHOW_BOXES,"Рамки включены"),"покажи рамки")
        add(VoiceCommand(VoiceAction.HIDE_BOXES,"Рамки скрыты"),"убери рамки","спрячь рамки")
        add(VoiceCommand(VoiceAction.CAMERA,"Камера: наведи телефон и скажи «подтвердить»"),"сделать фото","сделай фото","сфотографируй")
        add(VoiceCommand(VoiceAction.CAMERA_NOW,"Делаю фото сразу"),"сделать фото сразу","сделай фото сразу","сфотографируй сразу")
        add(VoiceCommand(VoiceAction.OPEN_CAMERA,"Открываю камеру"),"открой камеру")
        add(VoiceCommand(VoiceAction.GALLERY,"Открываю галерею"),"открыть галерею","открой галерею","из галереи")
        add(VoiceCommand(VoiceAction.SYSTEM_GALLERY,"Открываю системный выбор"),"системная галерея","открой системную галерею")
        add(VoiceCommand(VoiceAction.ANALYZE,"Проверяю фото"),"проверить фото","проверь фото","проверь снимок","проверь ещё раз","проверить ещё раз")
        add(VoiceCommand(VoiceAction.MODEL,"Выбрана YOLO26n","yolo26n"),"быстрая модель","быструю модель","маленькая модель","выбери быструю модель")
        add(VoiceCommand(VoiceAction.MODEL,"Выбрана YOLO26s","yolo26s"),"большая модель","большую модель","выбери большую модель")
        add(VoiceCommand(VoiceAction.MODEL,"Выбрана YOLO11n","yolo11n"),"старая модель","старую модель","выбери старую модель")
        add(VoiceCommand(VoiceAction.CROP,"Все культуры","all"),"все культуры","покажи все культуры")
        // All 15 taxonomy crops; the executor rejects a crop the selected model does not know.
        CropVocabulary.names.forEach {(crop,name) ->
            val phrases=CropVocabulary.spoken.getValue(crop)+CropVocabulary.chooseForms.getValue(crop).map {"выбери $it"}
            add(VoiceCommand(VoiceAction.CROP,name,crop),*phrases.distinct().toTypedArray())
        }
        add(VoiceCommand(VoiceAction.CANCEL,"Команда отменена"),"отмена","отмени","нет")
        add(VoiceCommand(VoiceAction.CONFIRM,"Подтверждаю"),"подтвердить","подтверди","да","выбрать фото","выбери это фото")
        add(VoiceCommand(VoiceAction.BACK,"Назад"),"назад","вернись назад")
        add(VoiceCommand(VoiceAction.EXIT,"Закрываю приложение"),"закрой приложение","выход")
        add(VoiceCommand(VoiceAction.HELP,"Голосовые команды"),"помощь","покажи команды","список моделей","какие модели")
        add(VoiceCommand(VoiceAction.CLOSE_HELP,"Закрываю подсказку"),"закрой подсказку","понятно")
        add(VoiceCommand(VoiceAction.LISTEN_ON,"Постоянное слушание включено"),"слушай постоянно","включи постоянное слушание")
        add(VoiceCommand(VoiceAction.LISTEN_OFF,"Микрофон выключен"),"стоп","выключи голос","выключи микрофон","перестань слушать")
        add(VoiceCommand(VoiceAction.THRESHOLD_UP,"Увеличиваю порог"),"увеличь порог","порог выше")
        add(VoiceCommand(VoiceAction.THRESHOLD_DOWN,"Уменьшаю порог"),"уменьши порог","порог ниже")
        add(VoiceCommand(VoiceAction.SCROLL,"Листаю вниз","down"),"прокрути вниз","листать вниз","ниже")
        add(VoiceCommand(VoiceAction.SCROLL,"Листаю вверх","up"),"прокрути вверх","листать вверх","выше")
        add(VoiceCommand(VoiceAction.SCROLL,"В начало","top"),"в начало","прокрути в начало")
        add(VoiceCommand(VoiceAction.SCROLL,"В конец","bottom"),"в конец","прокрути в конец")
        modelIds.forEachIndexed {index,id ->
            val number=RussianPhotoNumber.words(index+1)
            add(VoiceCommand(VoiceAction.MODEL,"Модель №${index+1}",id),"модель $number","выбери модель $number")
        }
        add(VoiceCommand(VoiceAction.FIRST_PHOTO,"Первое фото"),"первое фото","самое новое фото","новое фото")
        add(VoiceCommand(VoiceAction.LAST_PHOTO,"Последнее фото"),"последнее фото","самое старое фото","старое фото")
        add(VoiceCommand(VoiceAction.NEXT_PHOTO,"Следующее фото"),"следующее фото","дальше")
        add(VoiceCommand(VoiceAction.PREVIOUS_PHOTO,"Предыдущее фото"),"предыдущее фото")
        listOf("второе","третье","четвёртое","пятое","шестое","седьмое","восьмое","девятое","десятое").forEachIndexed {index,word ->
            add(VoiceCommand(VoiceAction.PHOTO_NUMBER,"Фото №${index+2}",(index+2).toString()),"$word фото")
        }
    }
    val phrases: List<String> get() = aliases.keys.toList()
    private val globalActions=setOf(VoiceAction.MODEL,VoiceAction.CROP,VoiceAction.HELP,VoiceAction.CLOSE_HELP,VoiceAction.LISTEN_ON,VoiceAction.LISTEN_OFF,VoiceAction.BACK,VoiceAction.EXIT,VoiceAction.CANCEL,VoiceAction.SCROLL,VoiceAction.THRESHOLD_UP,VoiceAction.THRESHOLD_DOWN)
    private fun phrasesFor(actions: Set<VoiceAction>)=aliases.filterValues {it.action in actions}.keys.toList()
    private val labActions=setOf(VoiceAction.NOTE,VoiceAction.JOURNAL,VoiceAction.SAVE_INSPECTION,VoiceAction.ASSESS,VoiceAction.COMPARE,VoiceAction.SPEAK_RESULT,VoiceAction.SILENCE,VoiceAction.SHOW_BOXES,VoiceAction.HIDE_BOXES,VoiceAction.ROW,VoiceAction.PLOT,VoiceAction.PLOTS,VoiceAction.SETTINGS,VoiceAction.KNOWLEDGE,VoiceAction.READ_KNOWLEDGE)
    const val MAX_ROW=999
    private const val SPOKEN_ROWS=200

    /** «Теплица 2» is heard as «теплица два»: lower case, «ё» as «е», digits as Russian words. */
    fun spokenPlot(name: String): String = name.lowercase(Locale.ROOT).replace('ё','е')
        .replace(Regex("[^\\p{L}0-9]+")," ").trim().split(" ").filter {it.isNotEmpty()}
        .joinToString(" ") {word -> word.toIntOrNull()?.takeIf {it in 1..999999}?.let(RussianPhotoNumber::words) ?: word}

    /** Main-screen grammar with «ряд N» and «участок <name>» for the plots that exist now. */
    fun mainGrammar(plots: Collection<String>, followUp: Boolean=false): List<String> =
        (grammar + listOf("ряд", "справка по листу", "чем лечить лист") + (1..SPOKEN_ROWS).map(RussianPhotoNumber::words) +
            plots.map {"участок ${spokenPlot(it)}"}.filter {it.length>"участок ".length} +
            // «да» is listened for only right after the app asked «Сохранить осмотр?».
            (if(followUp)listOf("да","подтвердить") else emptyList())).distinct()
    val noteReviewGrammar=listOf("подтвердить","да","отмена","нет","добавь заметку","очисти текст заметки","очисти заметку","стоп","не подтверждать","[unk]")
    fun journalGrammar(count: Int): List<String>? {
        if (count > 10000) return null
        return (phrasesFor(globalActions + setOf(VoiceAction.SPEAK_RESULT, VoiceAction.SILENCE, VoiceAction.NOTE)) +
            listOf("осмотр", "открой осмотр", "запись", "открой запись", "номер",
                "не открывай осмотр", "не читай результат", "[unk]") +
            (1..count.coerceAtLeast(1)).map(RussianPhotoNumber::words)).distinct()
    }
    private val knowledgeActions = setOf(VoiceAction.KNOWLEDGE, VoiceAction.READ_KNOWLEDGE,
        VoiceAction.KNOWLEDGE_NUMBER, VoiceAction.KNOWLEDGE_NEXT, VoiceAction.KNOWLEDGE_PREVIOUS)
    fun knowledgeGrammar(count: Int): List<String> = (phrasesFor(globalActions + knowledgeActions) +
        listOf("карточка", "карточка номер", "открой карточку", "открой карточку номер",
            "не открывай карточку", "не читай справку", "[unk]") +
        (1..count.coerceIn(1, 1000)).map(RussianPhotoNumber::words)).distinct()
    val grammar: List<String> get() = phrasesFor(globalActions + labActions + setOf(VoiceAction.CAMERA,VoiceAction.CAMERA_NOW,VoiceAction.OPEN_CAMERA,VoiceAction.GALLERY,VoiceAction.SYSTEM_GALLERY,VoiceAction.ANALYZE)) +
        listOf("порог процентов")+(10..80).map(RussianPhotoNumber::words)+listOf("не делай фото","не открывай галерею","не проверяй фото","не выключай голос","сегодня хорошая погода","[unk]")
    val cameraGrammar: List<String> get()=(phrasesFor(globalActions+setOf(VoiceAction.CONFIRM,VoiceAction.CAMERA_NOW,VoiceAction.CAMERA,VoiceAction.GALLERY,VoiceAction.CAMERA_GUIDANCE))+listOf("порог процентов")+(10..80).map(RussianPhotoNumber::words)+listOf("не подтверждать","не делай фото","не выключай голос","[unk]")).distinct()
    fun galleryGrammar(count: Int): List<String>? {
        if(count>10000)return null // Use the full Russian vocabulary for unusually large libraries.
        val galleryActions=setOf(VoiceAction.CONFIRM,VoiceAction.CANCEL,VoiceAction.BACK,VoiceAction.FIRST_PHOTO,VoiceAction.LAST_PHOTO,VoiceAction.NEXT_PHOTO,VoiceAction.PREVIOUS_PHOTO,VoiceAction.PHOTO_NUMBER,VoiceAction.SCROLL,VoiceAction.HELP,VoiceAction.CLOSE_HELP,VoiceAction.LISTEN_ON,VoiceAction.LISTEN_OFF,VoiceAction.EXIT)
        return (aliases.filterValues {it.action in galleryActions+globalActions+setOf(VoiceAction.CAMERA,VoiceAction.CAMERA_NOW,VoiceAction.GALLERY,VoiceAction.SYSTEM_GALLERY)}.keys.toList() +
            listOf("фото номер","выбери фото номер","номер","фото") +
            (1..count.coerceIn(1,10000)).map(RussianPhotoNumber::words) +
            listOf("порог процентов")+(10..80).map(RussianPhotoNumber::words)+
            listOf("не подтверждать","не выбирай фото","не делай фото","не выключай голос","сегодня хорошая погода","[unk]")).distinct()
    }
    fun parse(text: String, plots: Collection<String> = emptyList()): VoiceCommand? {
        val normalized = text.lowercase(Locale.ROOT).replace('ё','е')
            .replace(Regex("[.,!?;:]")," ").trim().replace(Regex("\\s+")," ")
        aliases.entries.firstOrNull { it.key.replace('ё','е') == normalized }?.value?.let {return it}
        val plantPrefix = listOf("растение ", "выбери растение ", "это растение ").firstOrNull { normalized.startsWith(it) }
        if (plantPrefix != null) {
            val name = normalized.removePrefix(plantPrefix).trim()
            if (name.isNotEmpty()) return VoiceCommand(VoiceAction.PLANT, "Растение: $name", name)
        }
        if (normalized.startsWith("сравни осмотры ")) {
            val parts = normalized.removePrefix("сравни осмотры ").split(" и ")
            val numbers = parts.mapNotNull(RussianPhotoNumber::parse)
            if (parts.size == 2 && numbers.size == 2) return VoiceCommand(VoiceAction.COMPARE_INSPECTIONS, "Сравнение осмотров", numbers.joinToString(":"))
        }
        if(normalized.startsWith("ряд ")) {
            val number=RussianPhotoNumber.parse(normalized.removePrefix("ряд ")) ?: return null
            return number.takeIf {it in 1..MAX_ROW}?.let {VoiceCommand(VoiceAction.ROW,"Ряд $it",it.toString())}
        }
        if(normalized.startsWith("участок ")) {
            val spoken=normalized.removePrefix("участок ")
            val plot=plots.firstOrNull {spokenPlot(it)==spoken} ?: return null
            return VoiceCommand(VoiceAction.PLOT,"Участок: $plot",plot)
        }
        if(normalized.startsWith("порог ")) {
            val number=RussianPhotoNumber.parse(normalized.removePrefix("порог ").removeSuffix(" процентов")) ?: return null
            return number.takeIf {it in 10..80}?.let {VoiceCommand(VoiceAction.THRESHOLD,"Порог $it%",it.toString())}
        }
        val number=RussianPhotoNumber.parse(normalized) ?: return null
        return VoiceCommand(VoiceAction.PHOTO_NUMBER,"Фото №$number",number.toString())
    }
    fun parseForScreen(text: String, screen: String, plots: Collection<String> = emptyList()): VoiceCommand? {
        if(screen=="note")return null
        com.agronomy.phytomonitor.actions.BrowseActions.parse(text, screen)?.let { return it }
        if (screen in setOf("main", "journal")) {
            val normalized = text.trim().lowercase().replace('ё', 'е').removeSuffix(".")
            val prefix = listOf("справка по листу ", "чем лечить лист ").firstOrNull { normalized.startsWith(it) }
            prefix?.let { RussianPhotoNumber.parse(normalized.removePrefix(it)) }?.let {
                return VoiceCommand(VoiceAction.LEAF_KNOWLEDGE, "Справка для листа $it", it.toString())
            }
        }
        if (screen == "plots") {
            com.agronomy.phytomonitor.actions.PlotActions.parse(text, plots)?.let { return it }
        }
        if (screen == "journal") {
            val normalized = text.lowercase(Locale.ROOT).replace('ё', 'е')
                .replace(Regex("[.,!?;:]"), " ").trim().replace(Regex("\\s+"), " ")
            val prefix = listOf("открой осмотр ", "открой запись ", "осмотр ", "запись ")
                .firstOrNull { normalized.startsWith(it) }
            val value = if (prefix != null) normalized.removePrefix(prefix) else normalized
            if ("фото" !in value) {
                RussianPhotoNumber.parse(value)?.let {
                    return VoiceCommand(VoiceAction.JOURNAL_NUMBER, "Осмотр №$it", it.toString())
                }
            }
        }
        if (screen == "knowledge") {
            val normalized = text.trim().lowercase().replace('ё', 'е').replace(Regex("[.,!?;:]"), "")
            val prefix = listOf("открой карточку номер ", "карточка номер ", "открой карточку ", "карточка ")
                .firstOrNull { normalized.startsWith(it) }
            val value = if (prefix != null) normalized.removePrefix(prefix) else normalized.takeIf { "фото" !in it }
            val number = value?.let(RussianPhotoNumber::parse)
            if (number != null) return VoiceCommand(VoiceAction.KNOWLEDGE_NUMBER, "Карточка №$number", number.toString())
        }
        val command=parse(text,plots) ?: return null
        if (screen == "journal" && command.action in setOf(VoiceAction.SPEAK_RESULT, VoiceAction.NOTE, VoiceAction.SILENCE, VoiceAction.READ_KNOWLEDGE)) return command
        if (screen == "knowledge" && command.action in knowledgeActions) return command
        if(screen=="note_review")return command.takeIf {it.action in setOf(VoiceAction.CONFIRM,VoiceAction.CANCEL,VoiceAction.NOTE,VoiceAction.CLEAR_NOTE,VoiceAction.LISTEN_OFF)}
        val plotActions=setOf(VoiceAction.ROW,VoiceAction.PLOT)
        if(command.action in plotActions && screen=="plots")return command
        if(command.action in labActions && screen!="main")return null
        val photoActions=setOf(VoiceAction.PHOTO_NUMBER,VoiceAction.FIRST_PHOTO,VoiceAction.LAST_PHOTO,VoiceAction.NEXT_PHOTO,VoiceAction.PREVIOUS_PHOTO)
        if(command.action in photoActions && screen!="gallery")return null
        // «да» on the main screen answers the app's own question; the executor refuses it otherwise.
        if(command.action==VoiceAction.ANALYZE && screen!="main")return null
        return command
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
