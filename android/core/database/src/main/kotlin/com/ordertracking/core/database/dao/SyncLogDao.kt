package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.ordertracking.core.database.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

private const val MAX_SYNC_LOG_ROWS = 200

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT $MAX_SYNC_LOG_ROWS")
    fun observeRecent(): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insert(entry: SyncLogEntity)

    @Query(
        "DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT $MAX_SYNC_LOG_ROWS)",
    )
    suspend fun trim()

    @Transaction
    suspend fun record(entry: SyncLogEntity) {
        insert(entry)
        trim()
    }
}
