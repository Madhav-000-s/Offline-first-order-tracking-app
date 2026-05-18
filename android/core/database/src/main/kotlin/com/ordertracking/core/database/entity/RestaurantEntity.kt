package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Paging-backed feed cache. */
@Entity(tableName = "restaurants", indices = [Index("name")])
data class RestaurantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cuisine: String,
    val rating: Double,
    val imageUrl: String,
    val lat: Double,
    val lng: Double,
)
