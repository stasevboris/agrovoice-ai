package com.agronomy.phytomonitor.journal

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Tables follow section 8.2 of docs/audits/AUDIT_AND_PLAN_2026-10-16.md (Room ↔ PostgreSQL). */
@Entity(tableName = "plot", indices = [Index(value = ["name"], unique = true)])
data class PlotEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = "field",
    val crop: String = "",
    val geometry: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "inspection",
    foreignKeys = [
        ForeignKey(entity = PlotEntity::class, parentColumns = ["id"], childColumns = ["plot_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("plot_id"), Index(value = ["report_name"], unique = true)]
)
data class InspectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "plot_id") val plotId: String?,
    val row: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double? = null,
    val note: String,
    @ColumnInfo(name = "user_review") val userReview: String,
    @ColumnInfo(name = "expert_status") val expertStatus: String = "none",
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "model_sha256") val modelSha256: String,
    /** File name of the analysis report in results/, the identity of one analysed result. */
    @ColumnInfo(name = "report_name") val reportName: String,
    @ColumnInfo(name = "report_sha256") val reportSha256: String,
    /** The analysis report exactly as saved: model, thresholds, quality and detections. */
    @ColumnInfo(name = "prediction_json") val predictionJson: String,
    @ColumnInfo(name = "sync_state") val syncState: String = "local",
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** One named plant inside a plot; later inspections of the same plant attach to it. */
@Entity(
    tableName = "plant",
    foreignKeys = [
        ForeignKey(entity = PlotEntity::class, parentColumns = ["id"], childColumns = ["plot_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("plot_id")]
)
data class PlantEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "plot_id") val plotId: String?,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "photo",
    foreignKeys = [
        ForeignKey(entity = InspectionEntity::class, parentColumns = ["id"], childColumns = ["inspection_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlantEntity::class, parentColumns = ["id"], childColumns = ["plant_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("inspection_id"), Index("plant_id")]
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspection_id") val inspectionId: String,
    @ColumnInfo(name = "plant_id") val plantId: String? = null,
    @ColumnInfo(name = "local_path") val localPath: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val uploaded: Boolean = false,
    @ColumnInfo(name = "frame_order", defaultValue = "0") val frameOrder: Int = 0,
    @ColumnInfo(name = "frame_role", defaultValue = "'unspecified'") val frameRole: String = "unspecified",
    @ColumnInfo(name = "report_name", defaultValue = "''") val reportName: String = "",
    @ColumnInfo(name = "report_sha256", defaultValue = "''") val reportSha256: String = "",
    /** Null means no independent analysis was supplied for this frame. */
    @ColumnInfo(name = "prediction_json", defaultValue = "NULL") val predictionJson: String? = null
)

@Entity(
    tableName = "detection",
    foreignKeys = [
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["id"], childColumns = ["photo_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("photo_id")]
)
data class DetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: String,
    @ColumnInfo(name = "class_code") val classCode: String,
    @ColumnInfo(name = "label_ru") val labelRu: String,
    val confidence: Double,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double
)

/** Filled from the verified knowledge base (K-01…K-03); empty until then. */
@Entity(tableName = "disease")
data class DiseaseEntity(
    @PrimaryKey val code: String,
    val crop: String,
    @ColumnInfo(name = "name_ru") val nameRu: String,
    val latin: String?,
    val symptoms: String,
    val prevention: String,
    @ColumnInfo(name = "kb_version") val kbVersion: String
)

/** Filled from the verified knowledge base (K-01…K-03); empty until then. */
@Entity(tableName = "treatment", indices = [Index("disease_code")])
data class TreatmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "disease_code") val diseaseCode: String,
    val product: String,
    @ColumnInfo(name = "active_substance") val activeSubstance: String,
    val rate: String,
    @ColumnInfo(name = "max_treatments") val maxTreatments: Int?,
    @ColumnInfo(name = "waiting_days") val waitingDays: Int?,
    @ColumnInfo(name = "hazard_class") val hazardClass: String?,
    val source: String,
    val year: Int?
)

@Entity(tableName = "kb_version")
data class KbVersionEntity(
    @PrimaryKey val version: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long,
    @ColumnInfo(name = "source_note") val sourceNote: String
)

/** Local changes waiting for the future sync server; nothing sends them yet. */
@Entity(tableName = "sync_queue", indices = [Index("entity_id")])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

data class PhotoWithDetections(
    @Embedded val photo: PhotoEntity,
    @Relation(parentColumn = "id", entityColumn = "photo_id")
    val detections: List<DetectionEntity>,
    @Relation(entity = PlantEntity::class, parentColumn = "plant_id", entityColumn = "id", projection = ["name"])
    val plantName: String? = null,
    @Relation(parentColumn = "id", entityColumn = "photo_id")
    val feedback: FrameFeedback? = null
)

data class InspectionWithDetails(
    @Embedded val inspection: InspectionEntity,
    @Relation(parentColumn = "plot_id", entityColumn = "id")
    val plot: PlotEntity?,
    @Relation(entity = PhotoEntity::class, parentColumn = "id", entityColumn = "inspection_id")
    val photos: List<PhotoWithDetections>
)
