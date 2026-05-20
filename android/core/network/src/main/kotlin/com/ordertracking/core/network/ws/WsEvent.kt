package com.ordertracking.core.network.ws

sealed interface WsEvent {
    data class OrderStatus(val orderId: String, val version: Long, val status: String, val seq: Long) : WsEvent
    data class CourierPosition(
        val orderId: String,
        val lat: Double,
        val lng: Double,
        val bearing: Float,
        val speedMps: Double,
        val seq: Long,
    ) : WsEvent
    data class Pong(val seq: Long) : WsEvent
    data object Connected : WsEvent
    data class Disconnected(val willReconnect: Boolean) : WsEvent
    /** A jump in `seq` means frames were dropped -- the caller should enqueue a delta sync (DESIGN.md §9). */
    data class GapDetected(val orderId: String, val expected: Long, val actual: Long) : WsEvent
}
