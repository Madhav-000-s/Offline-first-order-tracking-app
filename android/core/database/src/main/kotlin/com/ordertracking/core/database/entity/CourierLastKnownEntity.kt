package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Throttled to one write per ~15s (DESIGN.md §9) -- courier_position frames
 * themselves are in-memory-only; this table exists purely so a cold start
 * shows the marker at roughly the right place instead of jumping from the
 * restaurant.
 */
@Entity(tableName = "courier_last_known")
data class CourierLastKnownEntity(
    @PrimaryKey val orderId: String,
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val recordedAt: Instant,
)
