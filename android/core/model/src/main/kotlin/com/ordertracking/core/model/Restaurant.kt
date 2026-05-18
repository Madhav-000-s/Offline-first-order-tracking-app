package com.ordertracking.core.model

data class Restaurant(
    val id: String,
    val name: String,
    val cuisine: String,
    val rating: Double,
    val imageUrl: String,
    val lat: Double,
    val lng: Double,
)

data class MenuItem(
    val id: String,
    val restaurantId: String,
    val name: String,
    val description: String,
    val priceMinor: Long,
    val currency: String,
    val imageUrl: String,
)
