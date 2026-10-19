package com.agronomy.phytomonitor.journal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Rules version: raising it re-evaluates locked and unlocked rules without duplicating awards. */
const val ACHIEVEMENT_RULES_VERSION = 1

/**
 * A local optional badge. Awards never confirm agronomic qualification and never unlock treatments;
 * codes match docs/research/FEATURE_IDEAS_2026-10-19.md.
 */
data class AchievementDefinition(
    val id: String,
    val title: String,
    val condition: String
)

object AchievementCatalog {
    const val FIRST_INSPECTION = "first_inspection"
    const val COMPLETE_RECORD = "complete_record"
    const val BACKUP_CREATED = "backup_created"

    val all = listOf(
        AchievementDefinition(FIRST_INSPECTION, "Первый осмотр", "Один сохранённый осмотр с существующим фото"),
        AchievementDefinition(COMPLETE_RECORD, "Полевая запись", "Осмотр с участком, фото и заметкой"),
        AchievementDefinition(BACKUP_CREATED, "Данные сохранены", "Успешный полный архив журнала с фото")
    )

    fun definition(id: String): AchievementDefinition? = all.firstOrNull { it.id == id }
}

/** One award row per achievement; the unique key makes repeat unlocks impossible. */
@Entity(tableName = "achievement")
data class AchievementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "rules_version") val rulesVersion: Int,
    @ColumnInfo(name = "unlocked_at") val unlockedAt: Long,
    /** Null until the badge is announced; the badge is still awarded exactly once. */
    @ColumnInfo(name = "notified_at") val notifiedAt: Long? = null
)

/**
 * A durable journal fact that achievements can reckon with. Not exported: a restored copy must not
 * re-award badges earned on the other device, it recomputes them from its own restored records.
 */
@Entity(tableName = "journal_event")
data class JournalEventEntity(
    @PrimaryKey val kind: String,
    @ColumnInfo(name = "first_at") val firstAt: Long
) {
    companion object {
        const val FULL_BACKUP = "full_backup"
    }
}

data class JournalFacts(
    val withPhoto: Int,
    val complete: Int,
    val backups: Int
)

/** Pure conditions over unique journal facts; no counters that an import could inflate. */
object AchievementRules {
    fun unlocked(facts: JournalFacts): Set<String> = buildSet {
        if (facts.withPhoto >= 1) add(AchievementCatalog.FIRST_INSPECTION)
        if (facts.complete >= 1) add(AchievementCatalog.COMPLETE_RECORD)
        if (facts.backups >= 1) add(AchievementCatalog.BACKUP_CREATED)
    }
}
