package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val resource: String,
    val cursor: String,
    val lastSyncAt: Instant,
)
