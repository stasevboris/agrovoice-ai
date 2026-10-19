package com.agronomy.phytomonitor.journal

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agronomy.phytomonitor.InspectionEntry
import com.agronomy.phytomonitor.InspectionJournal
import com.agronomy.phytomonitor.JournalExport
import com.agronomy.phytomonitor.JournalReport
import com.agronomy.phytomonitor.SavedInspectionView
import com.agronomy.phytomonitor.ml.PhotoFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class SavedNoteDraft(val id: String, val original: String, val text: String, val updatedAt: Long)

data class JournalState(
    val entries: List<InspectionEntry> = emptyList(),
    val query: String = "",
    val filter: String = "all",
    val busy: Boolean = false,
    val report: JournalReport? = null,
    val message: String? = null,
    val opened: SavedInspectionView? = null,
    val openingId: String? = null,
    val noteDraft: SavedNoteDraft? = null,
    val scrollDirection: String = "",
    val scrollSequence: Long = 0
) {
    val visible: List<InspectionEntry>
        get() = entries.filter { entry ->
            (filter == "all" || entry.review == filter) &&
                (query.isBlank() || listOf(entry.plot, entry.note, entry.model)
                    .any { it.contains(query.trim(), ignoreCase = true) })
        }
}

/** Journal I/O has its own lifetime and state; opening a saved record never runs the detector. */
class JournalViewModel(application: Application) : AndroidViewModel(application) {
    private val journal = InspectionJournal(application)
    private val mutableState = MutableStateFlow(JournalState())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private lateinit var migration: Deferred<JournalReport?>
    private var connected = false
    private var opening: Job? = null

    fun connect(migration: Deferred<JournalReport?>) {
        if (connected) return
        connected = true
        this.migration = migration
        viewModelScope.launch {
            val report = migration.await()
            if (report?.worthShowing == true) mutableState.update { it.copy(report = report) }
            refresh()
        }
    }

    fun query(value: String) = mutableState.update { it.copy(query = value) }
    fun filter(value: String) = mutableState.update { it.copy(filter = value) }
    fun dismissReport() = mutableState.update { it.copy(report = null) }
    fun dismissMessage() = mutableState.update { it.copy(message = null) }
    fun scroll(direction: String) = mutableState.update {
        it.copy(scrollDirection = direction, scrollSequence = it.scrollSequence + 1)
    }

    fun refresh() = transact {
        val entries = withContext(Dispatchers.IO) { journal.list() }
        mutableState.update { it.copy(entries = entries) }
    }

    fun openInspection(id: String) {
        if (state.value.openingId == id || state.value.opened?.details?.inspection?.id == id) return
        opening?.cancel()
        mutableState.update { it.copy(opened = null, openingId = id, message = null) }
        opening = viewModelScope.launch {
            migration.await()
            try {
                val saved = withContext(Dispatchers.IO) {
                    val details = requireNotNull(journal.details(id)) { "Осмотр не найден в журнале" }
                    val path = details.photos.firstOrNull()?.photo?.localPath
                    val bitmap = path?.let(::File)?.takeIf { it.isFile }
                        ?.let { runCatching { PhotoFiles.decode(it) }.getOrNull() }
                    SavedInspectionView.of(details, bitmap)
                }
                mutableState.update { it.copy(opened = saved, openingId = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(openingId = null, message = error.message ?: "Не удалось открыть осмотр") }
            }
        }
    }

    fun closeInspection() {
        opening?.cancel()
        mutableState.update { it.copy(opened = null, openingId = null) }
    }

    fun beginNote(id: String): Boolean {
        val inspection = state.value.opened?.details?.inspection?.takeIf { it.id == id } ?: return false
        if (state.value.noteDraft?.id != id) {
            mutableState.update { it.copy(noteDraft = SavedNoteDraft(id, inspection.note, inspection.note, inspection.updatedAt), message = null) }
        }
        return true
    }

    fun editNote(text: String) = mutableState.update {
        it.copy(noteDraft = it.noteDraft?.copy(text = text.take(MAX_NOTE)))
    }

    fun cancelNote() = mutableState.update { it.copy(noteDraft = null) }

    fun saveNote(id: String, onSaved: (InspectionEntity, String) -> Unit) {
        val draft = state.value.noteDraft?.takeIf { it.id == id } ?: return
        transact {
            val updated = withContext(Dispatchers.IO) { journal.editNote(id, draft.updatedAt, draft.text) }
            val entries = withContext(Dispatchers.IO) { journal.list() }
            mutableState.update { state ->
                val opened = state.opened?.takeIf { it.details.inspection.id == id }
                state.copy(entries = entries, noteDraft = null,
                    opened = opened?.copy(details = opened.details.copy(inspection = updated)) ?: state.opened,
                    message = "Заметка сохранена.")
            }
            onSaved(updated, draft.original)
        }
    }

    fun export(uri: Uri, format: JournalExport = JournalExport.JSON) = transact {
        val message = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "Не удалось открыть файл" }.use { output ->
                when (format) {
                    JournalExport.JSON -> {
                        output.write(journal.export().toByteArray(Charsets.UTF_8))
                        "Журнал сохранён в JSON без фотографий."
                    }
                    JournalExport.CSV -> {
                        output.write(journal.exportCsv().toByteArray(Charsets.UTF_8))
                        "Таблица CSV сохранена без фотографий."
                    }
                    JournalExport.ARCHIVE -> "Архив сохранён, фото в нём: ${journal.exportArchive(output)}."
                }
            }
        }
        mutableState.update { it.copy(message = message) }
    }

    fun restore(uri: Uri) = transact {
        val report = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            requireNotNull(resolver.openInputStream(uri)) { "Не удалось открыть файл" }.use(journal::import)
        }
        val entries = withContext(Dispatchers.IO) { journal.list() }
        mutableState.update { it.copy(entries = entries, report = report) }
    }

    private fun transact(block: suspend () -> Unit) = viewModelScope.launch {
        writes.withLock {
            mutableState.update { it.copy(busy = true, message = null) }
            try {
                migration.await()
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.message ?: "Операция с журналом не выполнена") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
