package com.agronomy.phytomonitor.journal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

data class PlotSummary(
    @androidx.room.Embedded val plot: PlotEntity,
    val inspections: Int
)

/** One inspection with everything that belongs to it, written in one transaction. */
data class InspectionRecord(
    val inspection: InspectionEntity,
    val photo: PhotoEntity,
    val detections: List<DetectionEntity>,
    val additionalFrames: List<FrameRecord> = emptyList()
)

data class FrameRecord(val photo: PhotoEntity, val detections: List<DetectionEntity>)

@Dao
interface JournalDao {
    @Query("SELECT * FROM plot WHERE name = :name LIMIT 1")
    fun plotByName(name: String): PlotEntity?

    @Query("SELECT * FROM plant WHERE plot_id IS :plotId AND name = :name LIMIT 1")
    fun plantByName(plotId: String?, name: String): PlantEntity?

    @Query("SELECT * FROM plant WHERE id = :id")
    fun plant(id: String): PlantEntity?

    @Query("SELECT * FROM plant WHERE plot_id IS :plotId")
    fun plantsInPlot(plotId: String?): List<PlantEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPlant(plant: PlantEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPlot(plot: PlotEntity): Long

    @Query("SELECT * FROM plot WHERE id = :id")
    fun plot(id: String): PlotEntity?

    @Query(
        "SELECT plot.*, (SELECT COUNT(*) FROM inspection WHERE inspection.plot_id = plot.id) AS inspections " +
            "FROM plot"
    )
    fun plotsWithCounts(): List<PlotSummary>

    @Query("SELECT plot.*, (SELECT COUNT(*) FROM inspection WHERE inspection.plot_id = plot.id) AS inspections FROM plot")
    fun observePlots(): Flow<List<PlotSummary>>

    @Query("UPDATE plot SET name = :name, updated_at = :updatedAt WHERE id = :id")
    fun renamePlot(id: String, name: String, updatedAt: Long)

    @Query("DELETE FROM plot WHERE id = :id")
    fun deletePlot(id: String)

    @Transaction
    fun deleteNamedPlot(id: String, expectedName: String) {
        check(plot(id)?.name == expectedName) { "Участок изменился. Выбери его заново." }
        deletePlot(id)
    }

    @Query("SELECT * FROM inspection WHERE id = :id")
    fun inspection(id: String): InspectionEntity?

    @Query("SELECT * FROM inspection WHERE report_name = :reportName LIMIT 1")
    fun inspectionByReport(reportName: String): InspectionEntity?

    @Query("SELECT COUNT(*) FROM inspection")
    fun inspectionCount(): Int

    @Transaction
    @Query("SELECT * FROM inspection ORDER BY created_at DESC")
    fun inspections(): List<InspectionWithDetails>

    @Transaction
    @Query("SELECT * FROM inspection WHERE id = :id")
    fun inspectionDetails(id: String): InspectionWithDetails?

    @Upsert
    fun upsertInspection(inspection: InspectionEntity)

    @Upsert
    fun upsertPhoto(photo: PhotoEntity)

    @Query("SELECT * FROM photo WHERE id = :id")
    fun photo(id: String): PhotoEntity?

    @Query("SELECT * FROM frame_feedback WHERE photo_id = :photoId")
    fun frameFeedback(photoId: String): FrameFeedback?

    @Upsert
    fun upsertFrameFeedback(feedback: FrameFeedback)

    @Transaction
    fun editFrameFeedback(photoId: String, expectedRevision: Int, verdict: String, correction: String, now: Long): FrameFeedback {
        val photo = requireNotNull(photo(photoId)) { "Кадр не найден." }
        val existing = frameFeedback(photoId)
        check((existing?.revision ?: 0) == expectedRevision) { "Оценка кадра изменилась. Открой осмотр заново." }
        val version = FrameFeedback.versionOf(photo.predictionJson?.let { org.json.JSONObject(it) })
        val feedback = FrameFeedback(photoId, photo.sha256, FrameFeedback.evidenceSha(photo.reportSha256), version,
            verdict, correction.trim(), maxOf(now, (existing?.updatedAt ?: 0) + 1), expectedRevision + 1)
        feedback.validate()
        val inspection = requireNotNull(inspection(photo.inspectionId))
        upsertFrameFeedback(feedback)
        val updated = inspection.copy(updatedAt = maxOf(now, inspection.updatedAt + 1), syncState = "local")
        upsertInspection(updated)
        enqueueSync(SyncQueueEntity(entity = "inspection", entityId = inspection.id, operation = "upsert", createdAt = updated.updatedAt))
        return feedback
    }

    @Query("DELETE FROM detection WHERE photo_id = :photoId")
    fun deleteDetections(photoId: String)

    @Query("SELECT COUNT(*) FROM detection WHERE photo_id = :photoId")
    fun detectionCount(photoId: String): Int

    @Insert
    fun insertDetections(detections: List<DetectionEntity>)

    @Query("UPDATE photo SET local_path = :path WHERE inspection_id = :inspectionId")
    fun updatePhotoPath(inspectionId: String, path: String)

    @Query("UPDATE photo SET local_path = :path WHERE id = :photoId")
    fun updateFramePhotoPath(photoId: String, path: String)

    @Query("DELETE FROM inspection WHERE id = :id")
    fun deleteInspection(id: String)

    @Query("DELETE FROM sync_queue WHERE entity_id = :entityId")
    fun deleteSync(entityId: String)

    /** Only the removed inspection's test plot, and only if no inspection still refers to it. */
    @Query("DELETE FROM plot WHERE id = :id AND name LIKE :prefix || '%' AND id NOT IN (SELECT plot_id FROM inspection WHERE plot_id IS NOT NULL)")
    fun deleteUnusedTestPlot(id: String, prefix: String)

    @Transaction
    fun deleteTestInspection(id: String, prefix: String) {
        val plotId = inspection(id)?.plotId
        deleteInspection(id)
        deleteSync(id)
        if (plotId != null) {
            deleteUnusedTestPlot(plotId, prefix)
        }
    }

    @Insert
    fun enqueueSync(change: SyncQueueEntity)

    @Query("SELECT * FROM sync_queue ORDER BY id")
    fun pendingSync(): List<SyncQueueEntity>

    /** Edit metadata in place: photos, detections and the original report remain untouched. */
    @Transaction
    fun editNote(id: String, expectedUpdatedAt: Long, note: String, now: Long): InspectionEntity {
        require(note.length <= MAX_NOTE) { "Заметка слишком длинная: максимум $MAX_NOTE символов." }
        val current = requireNotNull(inspection(id)) { "Осмотр не найден в журнале." }
        check(current.updatedAt == expectedUpdatedAt) { "Осмотр изменился. Открой его заново перед исправлением заметки." }
        if (current.note == note) return current
        val updated = current.copy(note = note, updatedAt = maxOf(now, current.updatedAt + 1), syncState = "local")
        upsertInspection(updated)
        enqueueSync(SyncQueueEntity(entity = "inspection", entityId = id, operation = "upsert", createdAt = updated.updatedAt))
        return updated
    }

    /** One atomic inspection snapshot; existing observation rows survive metadata edits. */
    @Transaction
    fun saveRecord(record: InspectionRecord, change: SyncQueueEntity) {
        val frames = listOf(FrameRecord(record.photo, record.detections)) + record.additionalFrames
        require(frames.size in 1..MAX_INSPECTION_FRAMES && frames.map { it.photo.id }.distinct().size == frames.size)
        for (frame in frames) {
            require(frame.photo.inspectionId == record.inspection.id)
            val previous = photo(frame.photo.id)
            require(previous == null || previous.inspectionId == record.inspection.id) { "ID кадра уже принадлежит другому осмотру." }
        }
        upsertInspection(record.inspection)
        for (frame in frames) {
            val previous = photo(frame.photo.id)
            upsertPhoto(frame.photo)
            // Metadata edits/additional views must not replace the existing observation rows.
            if (previous == null || previous.predictionJson == null && detectionCount(frame.photo.id) == 0) {
                insertDetections(frame.detections)
            }
        }
        enqueueSync(change)
    }

    @Query("SELECT COUNT(*) FROM journal_event WHERE kind = :kind")
    fun eventCount(kind: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun recordEvent(event: JournalEventEntity): Long

    @Query("SELECT * FROM achievement")
    fun achievements(): List<AchievementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun award(achievement: AchievementEntity): Long

    @Query("UPDATE achievement SET notified_at = :notifiedAt WHERE id = :id AND notified_at IS NULL")
    fun markNotified(id: String, notifiedAt: Long)

    /**
     * Recomputes badge conditions from unique journal facts; awards are inserted once and never
     * deleted, so an imported backup of the same records cannot hand out a second copy.
     */
    @Transaction
    fun recomputeAchievements(photoAvailable: (String) -> Boolean, now: Long): List<AchievementEntity> {
        val facts = JournalFacts(
            withPhoto = inspections().count { details ->
                details.photos.firstOrNull()?.photo?.localPath?.let(photoAvailable) == true
            },
            complete = inspections().count { details ->
                details.inspection.plotId != null && details.inspection.note.isNotBlank() &&
                    details.photos.firstOrNull()?.photo?.localPath?.let(photoAvailable) == true
            },
            withLocation = inspections().count { details ->
                details.inspection.lat != null && details.inspection.lon != null &&
                    details.photos.firstOrNull()?.photo?.localPath?.let(photoAvailable) == true
            },
            backups = eventCount(JournalEventEntity.FULL_BACKUP)
        )
        val earned = achievements().map { it.id }.toSet()
        val newAwards = AchievementRules.unlocked(facts).filterNot { it in earned }.map { id ->
            AchievementEntity(id, ACHIEVEMENT_RULES_VERSION, unlockedAt = now)
        }
        for (award in newAwards) {
            award(award)
        }
        return newAwards
    }
}

@Database(
    entities = [
        PlotEntity::class,
        PlantEntity::class,
        InspectionEntity::class,
        PhotoEntity::class,
        FrameFeedback::class,
        DetectionEntity::class,
        DiseaseEntity::class,
        TreatmentEntity::class,
        KbVersionEntity::class,
        SyncQueueEntity::class,
        AchievementEntity::class,
        JournalEventEntity::class,
        com.agronomy.phytomonitor.sync.SyncRecord::class
    ],
    version = 6,
    exportSchema = true
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao
    abstract fun sync(): com.agronomy.phytomonitor.sync.SyncDao

    companion object {
        @Volatile private var instance: JournalDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS frame_feedback (photo_id TEXT NOT NULL, photo_sha256 TEXT NOT NULL, " +
                    "report_sha256 TEXT NOT NULL, analysis_version INTEGER, verdict TEXT NOT NULL, correction TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(photo_id), " +
                    "FOREIGN KEY(photo_id) REFERENCES photo(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        /** ALTER preserves existing photo IDs, detections, plant links and sync records. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo ADD COLUMN frame_order INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photo ADD COLUMN frame_role TEXT NOT NULL DEFAULT 'unspecified'")
                db.execSQL("ALTER TABLE photo ADD COLUMN report_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE photo ADD COLUMN report_sha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE photo ADD COLUMN prediction_json TEXT DEFAULT NULL")
                db.execSQL("UPDATE photo SET report_name = (SELECT report_name FROM inspection WHERE inspection.id = photo.inspection_id), " +
                    "report_sha256 = (SELECT report_sha256 FROM inspection WHERE inspection.id = photo.inspection_id), " +
                    "prediction_json = (SELECT prediction_json FROM inspection WHERE inspection.id = photo.inspection_id) " +
                    "WHERE id = inspection_id OR (SELECT COUNT(*) FROM photo AS sibling WHERE sibling.inspection_id = photo.inspection_id) = 1")
                db.execSQL("UPDATE photo SET frame_order = (SELECT COUNT(*) FROM photo AS sibling " +
                    "WHERE sibling.inspection_id = photo.inspection_id AND (" +
                    "(sibling.id = sibling.inspection_id AND photo.id != photo.inspection_id) OR " +
                    "((sibling.id = sibling.inspection_id) = (photo.id = photo.inspection_id) AND sibling.id < photo.id)))")
            }
        }

        /** Sync is opt-in: migration creates no outbox entries for the existing journal. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_record` (" +
                    "`account` TEXT NOT NULL, `localId` TEXT NOT NULL, `serverId` TEXT NOT NULL, " +
                    "`serverVersion` INTEGER NOT NULL, `snapshotUpdatedAt` INTEGER NOT NULL, " +
                    "`request` TEXT NOT NULL, `photoFiles` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`error` TEXT, `conflictJson` TEXT, `expertStatus` TEXT NOT NULL, " +
                    "PRIMARY KEY(`account`, `localId`))")
            }
        }

        /** v2 adds local optional badges; existing journals are kept and badges start unearned. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `achievement` (`id` TEXT NOT NULL, `rules_version` INTEGER NOT NULL, " +
                        "`unlocked_at` INTEGER NOT NULL, `notified_at` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `journal_event` (`kind` TEXT NOT NULL, `first_at` INTEGER NOT NULL, PRIMARY KEY(`kind`))"
                )
            }
        }

        /**
         * v3 adds plants and links existing photos to nothing (plant_id NULL): old inspections stay
         * without a plant until the user names one during a new inspection or an edit.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plant` (`id` TEXT NOT NULL, `plot_id` TEXT, `name` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`plot_id`) REFERENCES `plot`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plant_plot_id` ON `plant` (`plot_id`)")
                // A nullable REFERENCES column is supported with default NULL. Keeping the table
                // also keeps detections: dropping photo would cascade-delete its child rows.
                db.execSQL("ALTER TABLE `photo` ADD COLUMN `plant_id` TEXT REFERENCES `plant`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_plant_id` ON `photo` (`plant_id`)")
            }
        }

        fun get(context: Context): JournalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, JournalDatabase::class.java, "journal.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
