package com.agronomy.phytomonitor.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Account-scoped outbox. The request is retained unchanged until every photo is acknowledged. */
@Entity(tableName = "sync_record", primaryKeys = ["account", "localId"])
data class SyncRecord(
    val account: String,
    val localId: String,
    val serverId: String,
    val serverVersion: Long = 0,
    val snapshotUpdatedAt: Long,
    val request: String,
    val photoFiles: String,
    val state: String = "queued",
    val error: String? = null,
    val conflictJson: String? = null,
    val expertStatus: String = "unreviewed"
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_record WHERE account = :account AND localId = :id")
    fun record(account: String, id: String): SyncRecord?

    @Query("SELECT * FROM sync_record WHERE account = :account ORDER BY localId")
    fun records(account: String): List<SyncRecord>

    @Query("SELECT * FROM sync_record WHERE account = :account AND localId = :id")
    fun observe(account: String, id: String): Flow<SyncRecord?>

    @Query("SELECT * FROM sync_record WHERE account = :account ORDER BY localId")
    fun observeAll(account: String): Flow<List<SyncRecord>>

    @Upsert
    fun save(record: SyncRecord)
}
