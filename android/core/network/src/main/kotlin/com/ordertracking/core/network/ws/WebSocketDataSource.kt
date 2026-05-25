package com.ordertracking.core.network.ws

import kotlinx.coroutines.flow.SharedFlow

/**
 * Extracted so repositories can be tested against a fake event stream
 * without opening a real socket -- [OrderWebSocketClient] is the only
 * production implementation.
 */
interface WebSocketDataSource {
    val events: SharedFlow<WsEvent>
    fun connect(accessToken: String)
    fun subscribe(orderId: String)
    fun ping()
    fun disconnect()
}
