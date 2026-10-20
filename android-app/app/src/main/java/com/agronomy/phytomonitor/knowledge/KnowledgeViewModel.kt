package com.agronomy.phytomonitor.knowledge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.agronomy.phytomonitor.updates.KnowledgeUpdates

data class KnowledgeState(
    val catalog: KnowledgeCatalog? = null,
    val query: String = "",
    val crop: String = "all",
    val selectedCode: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val legacyClasses: Map<String, String> = emptyMap(),
    /** Null while the bundled reference book is used. */
    val updateVersion: String? = null
) {
    val selected: KnowledgeCard? get() = catalog?.cards?.firstOrNull { it.code == selectedCode }
    val visible: List<KnowledgeCard> get() = catalog?.search(query, crop).orEmpty()
    fun codeForClass(className: String): String? {
        val code = legacyClasses[className] ?: className
        return code.takeIf { candidate -> catalog?.cards?.any { it.code == candidate } == true }
    }
}

class KnowledgeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(KnowledgeState())
    val state = mutableState.asStateFlow()

    init { reload() }

    /** Called again after a verified reference book update; the open card stays selected if it still exists. */
    fun reload() {
        val application = getApplication<Application>()
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    KnowledgeUpdates.load(KnowledgeUpdates.store(application)) {
                        application.assets.open("knowledge/kb_v1.json").bufferedReader().use { it.readText() }
                    }
                }
                val legacy = withContext(Dispatchers.IO) {
                    val json = application.assets.open("knowledge/legacy_classes_v1.json").bufferedReader().use { JSONObject(it.readText()) }
                    json.keys().asSequence().associateWith(json::getString)
                }
                mutableState.update { current ->
                    current.copy(catalog = loaded.catalog, legacyClasses = legacy, loading = false, error = null,
                        updateVersion = loaded.release?.version,
                        selectedCode = current.selectedCode?.takeIf { code -> loaded.catalog.cards.any { it.code == code } })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = error.message ?: "Справочник недоступен") }
            }
        }
    }

    fun open(crop: String) = mutableState.update { it.copy(crop = crop, query = "", selectedCode = null) }
    fun query(text: String) = mutableState.update { it.copy(query = text) }
    fun crop(value: String) = mutableState.update { it.copy(crop = value, selectedCode = null) }
    fun select(code: String?) = mutableState.update { it.copy(selectedCode = code) }
    fun codeForClass(className: String): String? = state.value.codeForClass(className)
    fun spoken(): String = state.value.selected?.spoken() ?: state.value.visible.take(5).let { cards ->
        if (cards.isEmpty()) "Подходящих карточек нет. Измени культуру или поиск."
        else "Выбери карточку по номеру. " + cards.mapIndexed { index, card -> "${index + 1}. ${card.name}." }.joinToString(" ")
    }
}
