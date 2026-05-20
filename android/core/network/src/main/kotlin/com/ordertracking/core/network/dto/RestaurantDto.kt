package com.ordertracking.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class RestaurantDto(
    val id: String,
    val name: String,
    val cuisine: String,
    val rating: Double,
    val image_url: String,
    val lat: Double,
    val lng: Double,
    val version: Long,
    val updated_at: String,
    val deleted: Boolean = false,
)

@Serializable
data class MenuItemDto(
    val id: String,
    val restaurant_id: String,
    val name: String,
    val description: String,
    val price_minor: Long,
    val currency: String,
    val image_url: String,
    val version: Long,
    val updated_at: String,
    val deleted: Boolean = false,
)

@Serializable
data class RestaurantPageDto(
    val items: List<RestaurantDto>,
    val next_cursor: String?,
    val has_more: Boolean,
)
