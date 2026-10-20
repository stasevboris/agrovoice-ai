package com.agronomy.phytomonitor.updates

import android.content.Context
import com.agronomy.phytomonitor.knowledge.KnowledgeCatalog
import java.io.File

/** A downloaded reference book is used only after its SHA and its format are checked; otherwise the bundled one. */
object KnowledgeUpdates {
    fun store(context: Context) = VerifiedUpdateStore(File(context.filesDir, "updates"))

    data class Loaded(val catalog: KnowledgeCatalog, val release: UpdateRelease?)

    fun load(store: VerifiedUpdateStore, bundled: () -> String): Loaded {
        for (release in store.available("kb")) {
            val catalog = runCatching { KnowledgeCatalog.parse(store.file(release).readText(Charsets.UTF_8)) }.getOrNull()
            if (catalog != null) return Loaded(catalog, release)
        }
        return Loaded(KnowledgeCatalog.parse(bundled()), null)
    }

    /** The last reference book listed by the server (prepare_update_feed.py order), unless it is already active. */
    fun offered(releases: List<UpdateRelease>, active: UpdateRelease?): UpdateRelease? =
        releases.lastOrNull { it.kind == "kb" }?.takeIf { it.sha256 != active?.sha256 }

    fun describe(releases: List<UpdateRelease>, active: UpdateRelease?): String {
        val knowledge = offered(releases, active)
        val models = releases.filter { it.kind == "model" }
        val parts = mutableListOf<String>()
        parts += if (knowledge != null) "Доступен справочник версии ${knowledge.version}. Скажите «установи обновление справочника» или нажмите кнопку."
            else "Новых версий справочника нет."
        if (models.isNotEmpty()) {
            parts += "На сервере есть модели (${models.joinToString { it.version }}); установка моделей из обновлений в приложении пока не поддерживается."
        }
        return parts.joinToString(" ")
    }
}
