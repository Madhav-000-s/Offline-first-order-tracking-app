package com.ordertracking.core.data.merge

import com.ordertracking.core.model.OrderEvent
import com.ordertracking.core.model.OrderItem
import com.ordertracking.core.model.OrderStatus
import java.time.Instant

/**
 * Whatever arrived from REST, WS, or FCM, reduced to exactly what the merge
 * decision needs -- deliberately not the network DTO itself, so this engine
 * has zero dependency on Retrofit/serialization and can be unit-tested in
 * plain JUnit with no Android/Room/network in the picture at all.
 */
data class RemoteOrderSnapshot(
    val serverId: String,
    val clientLocalId: String,
    val restaurantId: String,
    val status: OrderStatus,
    val version: Long,
    val updatedAt: Instant,
    val eta: Instant?,
    val totalMinor: Long,
    val currency: String,
    val deliveryNote: String?,
    val tipMinor: Long,
    val routePolyline: String?,
    val placedAt: Instant,
    val items: List<OrderItem>,
    val events: List<OrderEvent>,
)
