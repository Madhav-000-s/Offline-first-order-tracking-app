package com.ordertracking.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class OrderItemDto(
    val id: String,
    val menu_item_id: String,
    val name_snapshot: String,
    val unit_price_minor: Long,
    val quantity: Int,
)

@Serializable
data class OrderEventDto(
    val id: String,
    val status: String,
    val occurred_at: String,
    val note: String? = null,
)

/** Mirrors the backend's OrderOut schema (backend/app/schemas/order.py) field for field. */
@Serializable
data class OrderDto(
    val id: String,
    val client_local_id: String,
    val restaurant_id: String,
    val status: String,
    val total_minor: Long,
    val currency: String,
    val eta: String? = null,
    val placed_at: String,
    val delivery_note: String? = null,
    val tip_minor: Long,
    val route_polyline: String? = null,
    val version: Long,
    val updated_at: String,
    val deleted: Boolean = false,
    val items: List<OrderItemDto> = emptyList(),
    val events: List<OrderEventDto> = emptyList(),
)

@Serializable
data class OrderItemInDto(val menu_item_id: String, val quantity: Int)

@Serializable
data class PlaceOrderRequestDto(
    val restaurant_id: String,
    val items: List<OrderItemInDto>,
    val delivery_note: String? = null,
    val tip_minor: Long = 0,
)

@Serializable
data class CancelOrderRequestDto(val reason: String? = null)
