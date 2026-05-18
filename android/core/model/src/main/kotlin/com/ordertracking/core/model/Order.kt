package com.ordertracking.core.model

import java.time.Instant

/**
 * Pure domain model -- no Room/Android annotations here (those live on
 * OrderEntity in :core:database). [localId] is the identity that matters:
 * it's a client-generated UUID that exists before the server has ever heard
 * of this order, which is what makes offline creation a non-special case
 * (DESIGN.md §4).
 */
data class Order(
    val localId: String,
    val serverId: String?,
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
    val items: List<OrderItem>,
    val events: List<OrderEvent>,
)

data class OrderItem(
    val id: String,
    val orderLocalId: String,
    val menuItemId: String,
    val nameSnapshot: String,
    val unitPriceMinor: Long,
    val quantity: Int,
)

data class OrderEvent(
    val id: String,
    val orderLocalId: String,
    val status: OrderStatus,
    val occurredAt: Instant,
    val note: String?,
)
