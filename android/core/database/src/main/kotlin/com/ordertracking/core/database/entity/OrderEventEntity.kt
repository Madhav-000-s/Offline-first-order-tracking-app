package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ordertracking.core.model.OrderStatus
import java.time.Instant

/** Append-only timeline -- this is what the tracking UI renders from. */
@Entity(
    tableName = "order_events",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["localId"],
            childColumns = ["orderLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("orderLocalId")],
)
data class OrderEventEntity(
    @PrimaryKey val id: String, // server event id, or "local:$uuid"
    val orderLocalId: String,
    val status: OrderStatus,
    val occurredAt: Instant,
    val note: String?,
)
