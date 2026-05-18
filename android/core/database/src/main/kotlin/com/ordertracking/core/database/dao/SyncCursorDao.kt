package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ordertracking.core.database.entity.SyncCursorEntity

@Dao
interface SyncCursorDao {

    @Query("SELECT * FROM sync_cursor WHERE resource = :resource")
    suspend fun find(resource: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)
}
