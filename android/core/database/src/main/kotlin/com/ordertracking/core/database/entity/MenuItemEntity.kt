package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "menu_items",
    foreignKeys = [
        ForeignKey(
            entity = RestaurantEntity::class,
            parentColumns = ["id"],
            childColumns = ["restaurantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("restaurantId")],
)
data class MenuItemEntity(
    @PrimaryKey val id: String,
    val restaurantId: String,
    val name: String,
    val description: String,
    val priceMinor: Long,
    val currency: String,
    val imageUrl: String,
)
