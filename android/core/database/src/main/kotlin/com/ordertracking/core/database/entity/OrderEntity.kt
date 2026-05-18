package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import java.time.Instant

/**
 * [localId] is the primary key, not the server id. An order exists before
 * the server has ever heard of it; making the PK server-assigned means
 * either a nullable PK or a row-identity change on first sync, which breaks
 * every Flow/LazyColumn key downstream. This one decision is what makes
 * offline creation clean (DESIGN.md §4).
 */
@Entity(
    tableName = "orders",
    indices = [Index("serverId", unique = true), Index("status")],
)
data class OrderEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val idempotencyKey: String,
    val restaurantId: String,
    val status: OrderStatus,
    val serverVersion: Long,
    val placedAtLocal: Instant,
    val serverUpdatedAt: Instant?,
    val totalMinor: Long,
    val currency: String,
    val syncState: SyncState,
    val lastError: String?,
    val etaAtServer: Instant?,
    val deliveryNote: String?,
    val tipMinor: Long,
    val routePolyline: String?,
)
