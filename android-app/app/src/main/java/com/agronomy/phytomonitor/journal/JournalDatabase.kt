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
import kotlinx.coroutines.flow.Flow

data class PlotSummary(
    @androidx.room.Embedded val plot: PlotEntity,
    val inspections: Int
)

/** One inspection with everything that belongs to it, written in one transaction. */
data class InspectionRecord(
    val inspection: InspectionEntity,
    val photo: PhotoEntity,
    val detections: List<DetectionEntity>
)

@Dao
interface JournalDao {
    @Query("SELECT * FROM plot WHERE name = :name LIMIT 1")
    fun plotByName(name: String): PlotEntity?

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

    @Query("DELETE FROM detection WHERE photo_id = :photoId")
    fun deleteDetections(photoId: String)

    @Insert
    fun insertDetections(detections: List<DetectionEntity>)

    @Query("UPDATE photo SET local_path = :path WHERE inspection_id = :inspectionId")
    fun updatePhotoPath(inspectionId: String, path: String)

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

    /** Inspection, photo and detections replace the previous version of the same inspection. */
    @Transaction
    fun saveRecord(record: InspectionRecord, change: SyncQueueEntity) {
        upsertInspection(record.inspection)
        upsertPhoto(record.photo)
        deleteDetections(record.photo.id)
        insertDetections(record.detections)
        enqueueSync(change)
    }
}

@Database(
    entities = [
        PlotEntity::class,
        InspectionEntity::class,
        PhotoEntity::class,
        DetectionEntity::class,
        DiseaseEntity::class,
        TreatmentEntity::class,
        KbVersionEntity::class,
        SyncQueueEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao

    companion object {
        @Volatile private var instance: JournalDatabase? = null

        fun get(context: Context): JournalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, JournalDatabase::class.java, "journal.db")
                .build()
                .also { instance = it }
        }
    }
}
