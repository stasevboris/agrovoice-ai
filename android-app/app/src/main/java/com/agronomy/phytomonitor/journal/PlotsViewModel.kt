package com.agronomy.phytomonitor.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agronomy.phytomonitor.actions.PlotTarget
import com.agronomy.phytomonitor.JournalReport
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PlotsState(val plots: List<PlotSummary> = emptyList(), val busy: Boolean = false,
    val message: String? = null, val messageIsError: Boolean = false, val pendingDeletion: PlotTarget? = null)

class PlotsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = JournalDatabase.get(application)
    private val plots = FieldPlots(database)
    private val settings = SettingsStore.get(application)
    private val mutableState = MutableStateFlow(PlotsState())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private lateinit var migration: Deferred<JournalReport?>
    private var connected = false

    fun connect(migration: Deferred<JournalReport?>) {
        if (connected) return
        connected = true
        this.migration = migration
        viewModelScope.launch {
            migration.await()
            database.journal().observePlots().collect { rows ->
                mutableState.update { it.copy(plots = rows.sortedBy { row -> row.plot.name.lowercase() }) }
            }
        }
    }

    fun createPlot(name: String) = change("Участок создан.") { plots.create(name) }

    fun renamePlot(id: String, name: String) = change("Участок переименован.") {
        val old = database.journal().plot(id)?.name
        plots.rename(id, name).also { result ->
            if (result is PlotChange.Done && old != null) {
                settings.replaceIfMatches(Keys.plot, old, result.plot.name).join()
            }
        }
    }

    fun askDelete(target: PlotTarget) = mutableState.update { it.copy(pendingDeletion = target, message = null) }
    fun cancelDelete() = mutableState.update { it.copy(pendingDeletion = null) }

    fun deletePlot(target: PlotTarget) = change("Участок удалён. Осмотры сохранены.") {
        database.journal().deleteNamedPlot(target.id, target.name)
        settings.replaceIfMatches(Keys.plot, target.name, "").join()
        mutableState.update { it.copy(pendingDeletion = null) }
        null
    }

    private fun change(success: String, operation: suspend () -> PlotChange?) = viewModelScope.launch {
        writes.withLock {
            mutableState.update { it.copy(busy = true, message = null, messageIsError = false) }
            try {
                migration.await()
                val outcome = withContext(Dispatchers.IO) { operation() }
                mutableState.update { it.copy(message = if (outcome is PlotChange.Rejected) outcome.reason else success,
                    messageIsError = outcome is PlotChange.Rejected) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.message ?: "Не удалось изменить участок", messageIsError = true) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
