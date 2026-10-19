package com.agronomy.phytomonitor.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class PlotsState(val plots: List<PlotSummary> = emptyList(), val busy: Boolean = false, val message: String? = null)

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

    fun createPlot(name: String) = change { plots.create(name) }

    fun renamePlot(id: String, name: String) = change {
        val old = database.journal().plot(id)?.name
        plots.rename(id, name).also { result ->
            if (result is PlotChange.Done && old != null) {
                settings.replaceIfMatches(Keys.plot, old, result.plot.name).join()
            }
        }
    }

    fun deletePlot(id: String) = change {
        val old = database.journal().plot(id)?.name
        plots.delete(id)
        if (old != null) settings.replaceIfMatches(Keys.plot, old, "").join()
        null
    }

    private fun change(operation: suspend () -> PlotChange?) = viewModelScope.launch {
        writes.withLock {
            mutableState.update { it.copy(busy = true, message = null) }
            try {
                migration.await()
                val outcome = withContext(Dispatchers.IO) { operation() }
                if (outcome is PlotChange.Rejected) mutableState.update { it.copy(message = outcome.reason) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.message ?: "Не удалось изменить участок") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
