package com.agronomy.phytomonitor.journal

import java.util.UUID

sealed interface PlotChange {
    data class Done(val plot: PlotEntity) : PlotChange
    data class Rejected(val reason: String) : PlotChange
}

/** Plots of the farm: created, renamed and removed by the agronomist. Inspections keep their history. */
class FieldPlots(database: JournalDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = database.journal()

    /** SQLite NOCASE folds only Latin letters, so Cyrillic names are sorted here. */
    fun list(): List<PlotSummary> = dao.plotsWithCounts().sortedBy { it.plot.name.lowercase() }

    fun create(name: String): PlotChange {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        nameProblem(clean, exceptId = null)?.let { return PlotChange.Rejected(it) }
        val plot = PlotEntity(UUID.randomUUID().toString(), clean, updatedAt = clock())
        dao.insertPlot(plot)
        return PlotChange.Done(plot)
    }

    /** Inspections refer to the plot by id, so they follow the new name. */
    fun rename(id: String, name: String): PlotChange {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        val plot = dao.plot(id) ?: return PlotChange.Rejected("Участок не найден.")
        nameProblem(clean, exceptId = id)?.let { return PlotChange.Rejected(it) }
        dao.renamePlot(id, clean, clock())
        return PlotChange.Done(plot.copy(name = clean, updatedAt = clock()))
    }

    /** The plot disappears; its inspections stay in the journal without a plot. */
    fun delete(id: String) {
        dao.deletePlot(id)
    }

    private fun nameProblem(name: String, exceptId: String?): String? {
        if (name.isEmpty()) {
            return "Введите название участка."
        }
        if (name.length > MAX_PLOT) {
            return "Название длиннее $MAX_PLOT символов."
        }
        val taken = dao.plotsWithCounts().any { it.plot.id != exceptId && it.plot.name.equals(name, ignoreCase = true) }
        return if (taken) "Участок «$name» уже есть." else null
    }
}
