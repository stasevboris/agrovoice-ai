package com.agronomy.phytomonitor.knowledge

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class KnowledgeSource(val id: String, val title: String, val url: String, val edition: String)
data class KnowledgeStatement(val text: String, val source: String, val locator: String)
data class KnowledgeCard(
    val code: String,
    val crop: String,
    val name: String,
    val status: String,
    val verifiedBy: String?,
    val reviewDue: String?,
    val missing: List<String>,
    val sections: Map<String, List<KnowledgeStatement>>,
    val sources: List<KnowledgeSource>
) {
    fun reviewed(today: LocalDate = LocalDate.now()): Boolean = status == "verified" &&
        !verifiedBy.isNullOrBlank() && missing.isEmpty() && sources.isNotEmpty() &&
        runCatching { !LocalDate.parse(reviewDue).isBefore(today) }.getOrDefault(false)

    fun spoken(today: LocalDate = LocalDate.now()): String = if (!reviewed(today)) {
        "Справка не проверена специалистом или требует пересмотра. Смотрите текст карточки."
    } else {
        name + ". " + sections.values.flatten().joinToString(" ") { it.text }
    }
}

data class KnowledgeCatalog(val version: String, val cards: List<KnowledgeCard>) {
    fun search(query: String, crop: String): List<KnowledgeCard> {
        val words = normalize(query).split(" ").filter { it.isNotEmpty() }
        return cards.filter { card ->
            (crop == "all" || card.crop == crop) && words.all { word ->
                word in normalize(card.name + " " + card.sections.values.flatten().joinToString(" ") { it.text })
            }
        }
    }

    companion object {
        val sectionNames = linkedMapOf("symptoms" to "Признаки", "conditions" to "Условия развития",
            "prevention" to "Профилактика", "agrotech" to "Агротехника")
        val gapNames = sectionNames + mapOf("treatments" to "Препараты", "fertilization" to "Питание")
        private fun normalize(text: String) = text.trim().lowercase().replace('ё', 'е')

        fun parse(text: String): KnowledgeCatalog {
            val document = JSONObject(text)
            require(document.getString("schema") == "agrovoice-kb-v1" && document.getString("region") == "BY") {
                "Неподдерживаемая версия или регион справочника"
            }
            val sources = document.getJSONArray("sources").objects().associate { source ->
                val id = source.getString("id")
                id to KnowledgeSource(id, source.getString("title"), source.getString("url"), source.getString("edition"))
            }
            val cards = document.getJSONArray("cards").objects().map { card ->
                val sections = sectionNames.keys.associateWith { section ->
                    card.getJSONArray(section).objects().map { statement ->
                        val source = statement.getString("source")
                        require(source in sources) { "Неизвестный источник $source" }
                        KnowledgeStatement(statement.getString("text"), source, statement.getString("locator"))
                    }
                }
                KnowledgeCard(card.getString("code"), card.getString("crop"), card.getString("name_ru"),
                    card.getString("status"), card.nullable("verified_by"), card.nullable("review_due"),
                    card.getJSONArray("missing").strings(), sections,
                    card.getJSONArray("sources").strings().map { sources.getValue(it) })
            }
            require(cards.map { it.code }.distinct().size == cards.size) { "Повторяющиеся карточки справочника" }
            return KnowledgeCatalog(document.getString("kb_version"), cards)
        }

        private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
        private fun JSONArray.strings() = (0 until length()).map(::getString)
        private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
