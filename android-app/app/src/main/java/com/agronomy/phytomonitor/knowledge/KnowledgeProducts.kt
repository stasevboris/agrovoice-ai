package com.agronomy.phytomonitor.knowledge

import org.json.JSONObject

data class KnowledgeTreatment(
    val product: String, val activeSubstance: String, val rate: String, val unit: String,
    val registeredIn: String, val registerYear: Int, val registerEntry: String, val userCategory: String,
    val source: KnowledgeSource, val locator: String, val maxTreatments: Int?, val waitingDays: Int?,
    val hazardClass: String?, val beesClass: String?, val note: String
) {
    fun text(): String = buildList {
        add("$product. Действующее вещество: $activeSubstance. Норма: $rate $unit.")
        add(if (userCategory == "professional") "Только для профессионального применения." else "Категория применения: личное подсобное хозяйство.")
        maxTreatments?.let { add("Максимум обработок: $it.") }
        waitingDays?.let { add("Срок ожидания: $it дней.") }
        hazardClass?.let { add("Класс опасности: $it.") }
        beesClass?.let { add("Опасность для пчёл: $it.") }
        if (note.isNotBlank()) add(note)
        add("Реестр РБ $registerYear, запись $registerEntry. ${source.id}: $locator.")
    }.joinToString(" ")
}

data class KnowledgeFertilization(
    val product: String, val nutrient: String, val rate: String, val unit: String, val method: String,
    val source: KnowledgeSource, val locator: String, val note: String
) {
    fun text() = "$product. Питательный элемент: $nutrient. Норма: $rate $unit. Способ: $method. $note ${source.id}: $locator."
}

/** No inferred rate or unit. The literal source fields must be present before a product is usable. */
object KnowledgeProducts {
    fun treatment(item: JSONObject, sources: Map<String, KnowledgeSource>): KnowledgeTreatment {
        val region = item.required("registered_in")
        require(region == "BY") { "Препарат не зарегистрирован для РБ" }
        val category = item.required("user_category")
        require(category in setOf("professional", "household")) { "Неизвестная категория применения" }
        val year = item.getInt("register_year")
        require(year >= 2000) { "Не указана редакция реестра" }
        return KnowledgeTreatment(item.required("product"), item.required("active_substance"), item.required("rate"),
            item.required("unit"), region, year, item.required("register_entry"), category,
            registrySource(item, sources), item.required("locator"), item.optionalInt("max_treatments", 1),
            item.optionalInt("waiting_days", 0), item.optionalText("hazard_class"), item.optionalText("bees_class"), item.optString("note"))
    }

    fun fertilization(item: JSONObject, sources: Map<String, KnowledgeSource>) = KnowledgeFertilization(
        item.required("product"), item.required("nutrient"), item.required("rate"), item.required("unit"),
        item.required("method"), registrySource(item, sources), item.required("locator"), item.optString("note"))

    private fun registrySource(item: JSONObject, sources: Map<String, KnowledgeSource>): KnowledgeSource {
        val source = requireNotNull(sources[item.required("source")]) { "Нет источника назначения в карточке" }
        require(source.kind == "by_register" && source.region == "BY") { "Назначение должно ссылаться на Госреестр РБ" }
        return source
    }

    private fun JSONObject.required(key: String): String = getString(key).also {
        require(it.isNotBlank()) { "Пустое поле назначения: $key" }
    }
    private fun JSONObject.optionalInt(key: String, minimum: Int): Int? =
        if (isNull(key)) null else getInt(key).also { require(it >= minimum) { "Некорректное ограничение $key" } }
    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else required(key)
}
