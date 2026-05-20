package com.ordertracking.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncChangesDto(
    val orders: List<OrderDto> = emptyList(),
    val restaurants: List<RestaurantDto> = emptyList(),
    val menu_items: List<MenuItemDto> = emptyList(),
)

@Serializable
data class SyncResponseDto(
    val changes: SyncChangesDto,
    val next_cursor: String,
    val has_more: Boolean,
    val server_time: String,
)

@Serializable
data class DeviceInDto(val fcm_token: String, val platform: String = "android")
