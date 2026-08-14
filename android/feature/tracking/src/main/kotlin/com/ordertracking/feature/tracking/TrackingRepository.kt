package com.ordertracking.feature.tracking

import com.ordertracking.core.data.merge.OrderWriter
import com.ordertracking.core.database.dao.CourierLastKnownDao
import com.ordertracking.core.database.entity.CourierLastKnownEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.network.ws.WebSocketDataSource
import com.ordertracking.core.network.ws.WsEvent
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CourierPositionUi(val lat: Double, val lng: Double, val bearingDegrees: Float, val speedMps: Double)

private val COURIER_PERSIST_INTERVAL: Duration = Duration.ofSeconds(15)

/**
 * Connected for as long as the tracking screen's ViewModel lives -- it owns
 * `start`/`stop`. This class owns the connection and the asymmetry
 * DESIGN.md §9 describes: `order_status` frames are durable truth and go
 * through the same [OrderWriter] every other channel uses, while
 * `courier_position` frames are in-memory-only, throttled to one write per
 * ~15s into `courier_last_known` purely so a cold start shows the marker at
 * roughly the right place instead of jumping from the restaurant.
 */
class TrackingRepository(
    private val wsClient: WebSocketDataSource,
    private val courierLastKnownDao: CourierLastKnownDao,
    private val orderWriter: OrderWriter,
    private val onGapDetected: (orderId: String) -> Unit,
) {
    private val _courierPosition = MutableStateFlow<CourierPositionUi?>(null)
    val courierPosition: StateFlow<CourierPositionUi?> = _courierPosition.asStateFlow()

    private var lastPersistAt: Instant = Instant.EPOCH
    private var collectJob: Job? = null

    fun start(scope: CoroutineScope, accessToken: String, orderId: String) {
        wsClient.connect(accessToken)
        wsClient.subscribe(orderId)
        collectJob?.cancel()
        collectJob = scope.launch {
            wsClient.events.collect { event ->
                when (event) {
                    is WsEvent.CourierPosition -> if (event.orderId == orderId) handlePosition(event)
                    is WsEvent.OrderStatus -> if (event.orderId == orderId) handleStatus(event)
                    is WsEvent.GapDetected -> if (event.orderId == orderId) onGapDetected(orderId)
                    else -> Unit
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        wsClient.disconnect()
    }

    fun lastKnownPosition(orderId: String) = courierLastKnownDao.observe(orderId)

    /**
     * The socket is an accelerator, never a second source of truth: the frame
     * is handed to [OrderWriter], which applies the identical version and FSM
     * guards a REST delta-sync page gets. A status the server has since
     * superseded is rejected here exactly as it would be over REST.
     */
    private suspend fun handleStatus(event: WsEvent.OrderStatus) {
        // An unrecognised status means this client is older than the server's
        // FSM; dropping the frame is right -- delta sync carries the full row.
        val status = runCatching { OrderStatus.valueOf(event.status) }.getOrNull() ?: return
        orderWriter.applyStatus(event.orderId, event.version, status, channel = "WS")
    }

    private suspend fun handlePosition(event: WsEvent.CourierPosition) {
        _courierPosition.value = CourierPositionUi(event.lat, event.lng, event.bearing, event.speedMps)

        val now = Instant.now()
        if (Duration.between(lastPersistAt, now) >= COURIER_PERSIST_INTERVAL) {
            courierLastKnownDao.upsert(
                CourierLastKnownEntity(orderId = event.orderId, lat = event.lat, lng = event.lng, bearing = event.bearing, recordedAt = now),
            )
            lastPersistAt = now
        }
    }
}
