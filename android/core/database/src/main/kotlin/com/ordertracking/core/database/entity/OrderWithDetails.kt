package com.ordertracking.core.database.entity

import androidx.room.Embedded
import androidx.room.Relation

/** What every order-facing screen actually observes as a `Flow`. */
data class OrderWithDetails(
    @Embedded val order: OrderEntity,
    @Relation(parentColumn = "localId", entityColumn = "orderLocalId")
    val items: List<OrderItemEntity>,
    @Relation(parentColumn = "localId", entityColumn = "orderLocalId")
    val events: List<OrderEventEntity>,
)
