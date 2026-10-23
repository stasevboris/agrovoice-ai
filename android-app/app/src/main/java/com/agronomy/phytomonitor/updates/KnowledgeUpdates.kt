package com.agronomy.phytomonitor.updates

import android.content.Context
import com.agronomy.phytomonitor.knowledge.KnowledgeCatalog
import java.io.File
import java.util.concurrent.CancellationException

/** A downloaded reference book is used only after its SHA and its format are checked; otherwise the bundled one. */
object KnowledgeUpdates {
    fun store(context: Context) = VerifiedUpdateStore(File(context.filesDir, "updates"))

    data class Loaded(val catalog: KnowledgeCatalog, val release: UpdateRelease?)

    fun load(store: VerifiedUpdateStore, bundled: () -> String): Loaded =
        usable(store) ?: Loaded(KnowledgeCatalog.parse(bundled()), null)

    /** The downloaded book actually in use, or null for the bundled one; SHA alone is not enough. */
    fun active(store: VerifiedUpdateStore): UpdateRelease? = usable(store)?.release

    /** Format check, then an atomic switch that keeps the book really in use as the rollback target. */
    fun install(store: VerifiedUpdateStore, release: UpdateRelease, active: () -> Boolean = { true },
                commit: (() -> Unit) -> Unit = { it() }) {
        require(release.kind == "kb")
        require(store.valid(release)) { "Размер или SHA справочника не совпадает" }
        try {
            KnowledgeCatalog.parse(store.file(release).readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Файл справочника не прошёл проверку формата. Используется прежняя версия.", error)
        }
        val previous = usable(store)?.release
        if (!active() || Thread.currentThread().isInterrupted) throw CancellationException("Установка справочника отменена")
        commit { store.activate(release, previous) }
    }

    private fun usable(store: VerifiedUpdateStore): Loaded? {
        for (release in store.available("kb")) {
            val catalog = runCatching { KnowledgeCatalog.parse(store.file(release).readText(Charsets.UTF_8)) }.getOrNull()
            if (catalog != null) return Loaded(catalog, release)
        }
        return null
    }

    /** The last reference book listed by the server (prepare_update_feed.py order), unless it is already active. */
    fun offered(releases: List<UpdateRelease>, active: UpdateRelease?): UpdateRelease? =
        releases.lastOrNull { it.kind == "kb" }?.takeIf { it.sha256 != active?.sha256 }

    fun describe(releases: List<UpdateRelease>, active: UpdateRelease?): String {
        val knowledge = offered(releases, active)
        val parts = mutableListOf<String>()
        parts += if (knowledge != null) "Доступен справочник версии ${knowledge.version}. Скажите «установи обновление справочника» или нажмите кнопку."
            else "Новых версий справочника нет."
        return parts.joinToString(" ")
    }
}
