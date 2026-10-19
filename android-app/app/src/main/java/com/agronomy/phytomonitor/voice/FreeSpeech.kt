package com.agronomy.phytomonitor.voice

import com.agronomy.phytomonitor.ml.CropVocabulary
import java.util.Locale

/** What a free Russian phrase means for the app. Nothing here runs an action: the executor checks it. */
sealed interface Understanding {
    data class Commands(val commands: List<VoiceCommand>) : Understanding
    data class Refusal(val text: String) : Understanding
    data object NotUnderstood : Understanding
}

/**
 * V-03/V-06: ordinary Russian requests without a list of fixed phrases. Hand-written rules on word stems,
 * numbers and connectors («и потом», «а затем»); no training and no model. A phrase with «не» never starts
 * a side effect (see NegationGuard). Out-of-scope requests (deleting, sending, doses) are refused.
 * Measured only on authored synthetic phrases: see docs/experiments/v06_intent_baseline.
 */
object FreeSpeech {
    private const val REFUSE_SCOPE = "Это действие вне возможностей AgroVoice."
    private const val REFUSE_TREATMENT = "Нет проверенной рекомендации из реестра РБ. Нужна проверка специалиста."
    private const val NOT_DONE = "Действие не выполняется."
    /** «прочитай результат, а затем сохрани»; « и » splits only when both halves are commands. */
    private val connectors = Regex("\\s*,?\\s*(?:а затем|и затем|и потом|после этого|затем|потом)\\s*,?\\s*|\\s+и\\s+")

    fun understand(text: String, screen: String, plots: Collection<String> = emptyList()): Understanding {
        if (screen == "note") {
            return Understanding.NotUnderstood
        }
        val phrase = normalize(text)
        if (phrase.isEmpty()) {
            return Understanding.NotUnderstood
        }
        if (screen == "plots") {
            com.agronomy.phytomonitor.actions.PlotActions.parse(phrase, plots)?.let {
                return Understanding.Commands(listOf(it))
            }
        }
        refusal(phrase)?.let { return Understanding.Refusal(it) }
        val parts = phrase.split(connectors).map { it.trim(' ', ',') }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val commands = parts.map { single(it, screen, plots) }
            if (commands.all { it is Understanding.Commands }) {
                // «покажи рамки и границы» is one request said twice, not two steps.
                val steps = commands.flatMap { (it as Understanding.Commands).commands }
                val distinct = steps.filterIndexed { index, step ->
                    index == 0 || step.action != steps[index - 1].action || step.value != steps[index - 1].value
                }
                return Understanding.Commands(distinct)
            }
        }
        return single(phrase, screen, plots)
    }

    fun normalize(text: String): String = text.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[«»\"“”„.!?;:()\\-—–]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun refusal(phrase: String): String? {
        val words = words(phrase)
        val treatment = listOf("дозировк", "доз", "препарат", "фунгицид", "концентрац", "норму опрыск", "чем обработать", "чем лечить", "средство")
        if (treatment.any { phrase.contains(it) }) {
            return REFUSE_TREATMENT
        }
        val outside = listOf("удали", "сотри", "отправь", "почт", "разблокир", "настройках android", "игнорируй")
        if (outside.any { stem -> words.any { it.startsWith(stem) } || phrase.contains(stem) }) {
            return REFUSE_SCOPE
        }
        return null
    }

    private fun single(phrase: String, screen: String, plots: Collection<String>): Understanding {
        VoiceCommands.parseForScreen(phrase, screen, plots)?.let { return Understanding.Commands(listOf(it)) }
        val words = words(phrase)
        val command = match(phrase, words, screen) ?: return Understanding.NotUnderstood
        val negated = words.any { it == "не" || it == "нельзя" } || phrase.contains("не нужно") || phrase.contains("не надо")
        if (negated && negationBlocks(command, words)) {
            return Understanding.Refusal(NOT_DONE)
        }
        return Understanding.Commands(listOf(command))
    }

    /** Mirrors NegationGuard for phrases: «не» stops side effects, not cancelling or silencing. */
    private fun negationBlocks(command: VoiceCommand, words: List<String>): Boolean = when (command.action) {
        VoiceAction.CANCEL, VoiceAction.SILENCE, VoiceAction.BACK, VoiceAction.HIDE_BOXES, VoiceAction.CROP,
        VoiceAction.CLOSE_HELP, VoiceAction.SCROLL -> false
        VoiceAction.ASSESS -> command.value == "correct"
        VoiceAction.LISTEN_OFF -> words.zipWithNext().any { (a, b) -> a == "не" && listOf("выключ", "отключ", "перестав", "прекращ").any { b.startsWith(it) } }
        else -> true
    }

    private fun match(phrase: String, words: List<String>, screen: String): VoiceCommand? {
        fun has(vararg stems: String) = stems.any { stem -> words.any { it.startsWith(stem) } }
        fun says(vararg parts: String) = parts.any { phrase.contains(it) }
        fun command(action: VoiceAction, value: String = "", label: String = action.name) = VoiceCommand(action, label, value)

        if (screen == "note_review") {
            return when {
                has("отмен", "откаж") || says("не добавляй", "не нужен", "не нужна") -> command(VoiceAction.CANCEL)
                has("подтвержд", "оставь", "верно", "правильн") -> command(VoiceAction.CONFIRM)
                else -> null
            }
        }
        scroll(phrase, words)?.let { return it }
        if (screen == "journal") {
            if (has("заметк", "комментари") && has("исправ", "измен", "добав", "запиш", "надикт")) {
                return command(VoiceAction.NOTE)
            }
            if (has("осмотр", "запис") && has("открой", "покажи", "открыть", "выбери")) {
                number(words)?.let { return command(VoiceAction.JOURNAL_NUMBER, it.toString()) }
            }
            if (has("прочита", "прочти", "озвуч", "расскаж") && has("результат", "осмотр", "запис")) {
                return command(VoiceAction.SPEAK_RESULT)
            }
        }
        return when {
            has("помолч") -> command(VoiceAction.SILENCE)
            has("отмен", "передумал", "отбой") || says("прекрати текущ") -> command(VoiceAction.CANCEL)
            says("не слушай", "прекрати слушать", "перестань слушать", "переставай слушать") || (has("отключ", "выключ") && has("микрофон")) ->
                command(VoiceAction.LISTEN_OFF)
            (has("слушай") && has("постоян", "следующ")) || has("непрерывн") -> command(VoiceAction.LISTEN_ON)
            has("помолч", "хватит") || says("не читай", "прерви озвуч", "останови чтение", "останови озвуч") -> command(VoiceAction.SILENCE)
            has("подсказ", "справк") && has("убер", "закро") -> command(VoiceAction.CLOSE_HELP)
            has("помощ") || says("как тобой управлять", "голосовые действия", "доступные команды") -> command(VoiceAction.HELP)
            (has("заверш", "выйд", "выйти") && !has("осмотр")) || says("закрой приложение") -> command(VoiceAction.EXIT)
            says("предыдущий экран", "давай обратно", "шаг назад") -> command(VoiceAction.BACK)
            screen == "gallery" -> gallery(phrase, words)
            screen == "camera" && (has("снимай", "фотографир", "затвор", "делай") || says("кадр готов", "подтвержд")) ->
                if (has("подтвержд")) command(VoiceAction.CONFIRM) else command(VoiceAction.CAMERA_NOW)
            has("назад", "обратно") || says("предыдущий экран") -> command(VoiceAction.BACK)
            else -> mainScreen(phrase, words, screen)
        }
    }

    private fun gallery(phrase: String, words: List<String>): VoiceCommand? {
        fun has(vararg stems: String) = stems.any { stem -> words.any { it.startsWith(stem) } }
        if (has("свеж") || (has("первой", "первую") && !has("номер"))) {
            return VoiceCommand(VoiceAction.FIRST_PHOTO, "Первое фото")
        }
        if (has("стар", "последн")) {
            return VoiceCommand(VoiceAction.LAST_PHOTO, "Последнее фото")
        }
        number(words)?.let { return VoiceCommand(VoiceAction.PHOTO_NUMBER, "Фото №$it", it.toString()) }
        return when {
            has("следующ", "вперед", "дальше") -> VoiceCommand(VoiceAction.NEXT_PHOTO, "Следующее фото")
            phrase.contains("перед этим") || (has("назад") && has("фото", "снимок", "кадр")) -> VoiceCommand(VoiceAction.PREVIOUS_PHOTO, "Предыдущее фото")
            has("берем", "бери", "используй", "подтвержд", "выбираю") || words.firstOrNull() == "да" -> VoiceCommand(VoiceAction.CONFIRM, "Подтверждаю")
            has("назад", "обратно") -> VoiceCommand(VoiceAction.BACK, "Назад")
            else -> null
        }
    }

    private fun mainScreen(phrase: String, words: List<String>, screen: String): VoiceCommand? {
        fun has(vararg stems: String) = stems.any { stem -> words.any { it.startsWith(stem) } }
        fun says(vararg parts: String) = parts.any { phrase.contains(it) }
        val result = has("результат", "вывод", "ответ", "диагноз") || says("что показала", "что с растением")
        val journal = has("журнал", "истори") || says("предыдущие осмотры")
        val save = has("сохран", "запиши", "занеси", "останется", "оставить", "добавь") && (journal || has("осмотр", "проверк", "результат"))
        return when {
            has("порог") -> threshold(phrase, words)
            screen != "main" && screen != "plots" -> null
            has("рам", "границ", "прямоугольн") -> if (has("скрой", "убер", "спрячь")) VoiceCommand(VoiceAction.HIDE_BOXES, "Рамки скрыты") else VoiceCommand(VoiceAction.SHOW_BOXES, "Рамки включены")
            has("сравни") || says("разными нейросетями", "разными моделями") -> VoiceCommand(VoiceAction.COMPARE, "Сравниваю модели")
            has("ошиблась", "ошибся") || (has("ошибочн", "неверн") && (result || has("пометь", "отметь"))) ->
                VoiceCommand(VoiceAction.ASSESS, "Оценка: неверно", "incorrect")
            has("сомнева") || says("не могу подтвердить") -> VoiceCommand(VoiceAction.ASSESS, "Оценка: не уверен", "uncertain")
            (has("правильн", "верн") || says("согласен с результатом")) && has("отметь", "пометь", "согласен", "ответила") ->
                VoiceCommand(VoiceAction.ASSESS, "Оценка: верно", "correct")
            has("заметк", "комментари", "надиктов") -> VoiceCommand(VoiceAction.NOTE, "Заметка")
            save || (has("сохран", "сохраняй") && words.size <= 3) -> VoiceCommand(VoiceAction.SAVE_INSPECTION, "Сохраняю осмотр")
            journal -> VoiceCommand(VoiceAction.JOURNAL, "Журнал осмотров")
            has("прочита", "прочти", "озвуч", "расскаж", "проговори", "услышать") && result || says("какой результат", "что показала") ->
                VoiceCommand(VoiceAction.SPEAK_RESULT, "Читаю результат")
            has("системн") || says("вне приложения", "фотографии телефона") -> VoiceCommand(VoiceAction.SYSTEM_GALLERY, "Системная галерея")
            has("галере") || says("мои фотографии", "уже снятый", "сохраненным фотографиям", "покажи список", "снимок из") ->
                VoiceCommand(VoiceAction.GALLERY, "Открываю галерею")
            has("камер", "видоискател") || says("сфотографировать", "снять лист", "к съемке") -> VoiceCommand(VoiceAction.OPEN_CAMERA, "Открываю камеру")
            has("модел", "йоло", "yolo") && model(phrase, words) != null -> model(phrase, words)
            has("проверь", "проверить", "анализ", "распознавани", "исследуй") || says("посмотри, что", "посмотри что", "чем болен") ->
                VoiceCommand(VoiceAction.ANALYZE, "Проверяю фото")
            else -> crop(words)
        }
    }

    private fun threshold(phrase: String, words: List<String>): VoiceCommand? {
        fun has(vararg stems: String) = stems.any { stem -> words.any { it.startsWith(stem) } }
        val value = number(words)
        if (value != null) {
            val signed = if (has("минус")) -value else value
            return VoiceCommand(VoiceAction.THRESHOLD, "Порог $signed%", signed.toString())
        }
        return when {
            has("подними", "повыс", "выше", "увелич") -> VoiceCommand(VoiceAction.THRESHOLD_UP, "Увеличиваю порог")
            has("сниз", "меньше", "ниже", "уменьш") -> VoiceCommand(VoiceAction.THRESHOLD_DOWN, "Уменьшаю порог")
            else -> null
        }
    }

    private fun model(phrase: String, words: List<String>): VoiceCommand? {
        val compact = phrase.replace(" ", "")
        return when {
            compact.contains("yolo26s") || phrase.contains("двадцать шесть эс") || words.any { it.startsWith("больш") } ->
                VoiceCommand(VoiceAction.MODEL, "Выбрана YOLO26s", "yolo26s")
            compact.contains("yolo26n") || phrase.contains("двадцать шесть эн") || words.any { it.startsWith("быстр") } ->
                VoiceCommand(VoiceAction.MODEL, "Выбрана YOLO26n", "yolo26n")
            else -> null
        }
    }

    /** The last crop named wins: «не картофель, выбери томат» chooses tomato. */
    private fun crop(words: List<String>): VoiceCommand? {
        val allCrops = words.any { it.startsWith("все") } && words.any { it.startsWith("культур") }
        if (allCrops || (words.any { it.startsWith("огранич") } && words.any { it.startsWith("культур") })) {
            return VoiceCommand(VoiceAction.CROP, "Все культуры", "all")
        }
        var found: String? = null
        for (word in words) {
            CropVocabulary.names.keys.firstOrNull { crop -> cropForms(crop).any { form -> sameCropWord(word, form) } }?.let { found = it }
        }
        val crop = found ?: return null
        return VoiceCommand(VoiceAction.CROP, CropVocabulary.names.getValue(crop), crop)
    }

    private fun cropForms(crop: String): List<String> =
        (CropVocabulary.spoken.getValue(crop) + CropVocabulary.chooseForms.getValue(crop)).map { it.replace('ё', 'е') }.distinct()

    /**
     * Long names match by stem («помидоров» → «помидоры»); short ones only as a whole word: a stem like
     * «со» or «пере» would turn «сохранение» or «переключись» into a crop.
     */
    private fun sameCropWord(word: String, form: String): Boolean =
        word == form || (form.length >= 6 && word.startsWith(form.dropLast(2)))

    private fun scroll(phrase: String, words: List<String>): VoiceCommand? {
        fun has(vararg stems: String) = stems.any { stem -> words.any { it.startsWith(stem) } }
        fun says(vararg parts: String) = parts.any { phrase.contains(it) }
        if (has("порог")) {
            return null
        }
        val asked = has("прокрут", "листай", "пролист", "опусти", "подними") ||
            says("записи ниже", "верх журнала", "в самое начало", "в самый низ", "до конца")
        if (!asked) {
            return null
        }
        val direction = when {
            says("в начало", "самое начало", "верх журнала", "наверх") -> "top"
            says("самый низ", "до конца", "в конец") -> "bottom"
            has("вниз", "ниже", "нижн") || has("опусти") -> "down"
            has("вверх", "выше") || has("подними") -> "up"
            else -> return null
        }
        return VoiceCommand(VoiceAction.SCROLL, "Прокрутка", direction)
    }

    /** A number said as digits, cardinal words or an ordinal («седьмой», «двадцать первый»). */
    fun number(words: List<String>): Int? {
        words.firstOrNull { it.all(Char::isDigit) }?.let { return it.toIntOrNull() }
        val ordinals = mapOf(
            "перв" to 1, "втор" to 2, "трет" to 3, "четверт" to 4, "пят" to 5, "шест" to 6, "седьм" to 7,
            "восьм" to 8, "девят" to 9, "десят" to 10
        )
        val tens = mapOf("двадцат" to 20, "тридцат" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90, "сто" to 100)
        // «двадцать первый»: tens followed by an ordinal, before the cardinal reading takes «двадцать» alone.
        for ((index, word) in words.withIndex()) {
            val ten = tens.entries.firstOrNull { word == it.key || (it.key != "сто" && word.startsWith(it.key)) }?.value ?: continue
            val next = words.getOrNull(index + 1) ?: continue
            val unit = ordinals.entries.firstOrNull { next.startsWith(it.key) }?.value ?: continue
            return ten + unit
        }
        val cardinal = words.dropWhile { RussianPhotoNumber.parse(it) == null && it != "ноль" }
        if (cardinal.firstOrNull() == "ноль") {
            return 0
        }
        for (length in minOf(4, cardinal.size) downTo 1) {
            RussianPhotoNumber.parse(cardinal.take(length).joinToString(" "))?.let { return it }
        }
        for ((index, word) in words.withIndex()) {
            val ten = tens.entries.firstOrNull { word == it.key || (word.startsWith(it.key) && word.length > it.key.length + 1) }?.value
            if (ten != null) {
                val next = words.getOrNull(index + 1)
                val unit = next?.let { candidate -> ordinals.entries.firstOrNull { candidate.startsWith(it.key) }?.value }
                return ten + (unit ?: 0)
            }
            ordinals.entries.firstOrNull { word.startsWith(it.key) && !word.startsWith("пятьдесят") && !word.startsWith("шестьдесят") }?.let { return it.value }
        }
        return null
    }

    private fun words(phrase: String): List<String> = phrase.split(Regex("[^\\p{L}0-9]+")).filter { it.isNotEmpty() }
}
