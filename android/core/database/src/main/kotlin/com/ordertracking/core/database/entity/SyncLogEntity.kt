package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A debug drawer that shows the last N merge decisions ("rejected WS v6,
 * local at v7") is the single best interview demo in the project -- it
 * makes the invisible sync engine visible in fifteen seconds (DESIGN.md §4).
 * Ring-buffered by the DAO (oldest rows trimmed past a max count), not by a
 * time-based TTL.
 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Instant,
    val orderLocalId: String,
    val channel: String, // REST | WS | FCM
    val decision: String, // e.g. "REJECT_STALE", "ACCEPT", "REJECT_REGRESSION"
    val detail: String,
)
