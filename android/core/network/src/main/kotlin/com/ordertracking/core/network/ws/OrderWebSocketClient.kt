package com.ordertracking.core.network.ws

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Thin wrapper around one OkHttp WebSocket connection. The token goes in the
 * first frame, never the query string, so it never lands in a server access
 * log (DESIGN.md §9). Reconnect-with-backoff and lifecycle scoping are the
 * caller's job (a repository, scoped to `repeatOnLifecycle`) -- this class
 * only owns a single connection's framing.
 */
class OrderWebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val wsUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val lastSeqByOrder = mutableMapOf<String, Long>()

    fun connect(accessToken: String) {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
        // Token in the first frame, never the query string or a header that
        // could end up in a server access log.
        webSocket?.send("""{"token":"$accessToken"}""")
    }

    fun subscribe(orderId: String) {
        webSocket?.send("""{"type":"subscribe","order_id":"$orderId"}""")
    }

    fun ping() {
        webSocket?.send("""{"type":"ping"}""")
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _events.tryEmit(WsEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseFrame(text)?.let { _events.tryEmit(it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _events.tryEmit(WsEvent.Disconnected(willReconnect = code != 1000))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _events.tryEmit(WsEvent.Disconnected(willReconnect = true))
        }
    }

    private fun parseFrame(text: String): WsEvent? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L

        return when (type) {
            "order_status" -> {
                val orderId = obj["order_id"]?.jsonPrimitive?.content ?: return null
                checkGap(orderId, seq)?.let { _events.tryEmit(it) }
                WsEvent.OrderStatus(
                    orderId = orderId,
                    version = obj["version"]?.jsonPrimitive?.longOrNull ?: 0L,
                    status = obj["data"]?.jsonObject?.get("status")?.jsonPrimitive?.content ?: return null,
                    seq = seq,
                )
            }
            "courier_position" -> {
                val orderId = obj["order_id"]?.jsonPrimitive?.content ?: return null
                val data = obj["data"]?.jsonObject ?: return null
                checkGap(orderId, seq)?.let { _events.tryEmit(it) }
                WsEvent.CourierPosition(
                    orderId = orderId,
                    lat = data["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                    lng = data["lng"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                    bearing = data["bearing"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    speedMps = data["speed_mps"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    seq = seq,
                )
            }
            "pong" -> WsEvent.Pong(seq)
            else -> null
        }
    }

    /** Monotonic `seq` per connection; a jump means frames were dropped. */
    private fun checkGap(orderId: String, seq: Long): WsEvent.GapDetected? {
        val last = lastSeqByOrder[orderId]
        lastSeqByOrder[orderId] = seq
        return if (last != null && seq > last + 1) {
            WsEvent.GapDetected(orderId, expected = last + 1, actual = seq)
        } else {
            null
        }
    }
}
