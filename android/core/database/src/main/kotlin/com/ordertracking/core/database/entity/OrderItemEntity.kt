package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "order_items",
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
data class OrderItemEntity(
    @PrimaryKey val id: String,
    val orderLocalId: String,
    val menuItemId: String,
    // Denormalised on purpose: menu changes must not rewrite order history.
    val nameSnapshot: String,
    val unitPriceMinor: Long,
    val quantity: Int,
)
