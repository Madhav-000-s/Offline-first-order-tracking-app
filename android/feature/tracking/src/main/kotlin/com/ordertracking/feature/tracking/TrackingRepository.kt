package com.ordertracking.feature.tracking

import com.ordertracking.core.database.dao.CourierLastKnownDao
import com.ordertracking.core.database.entity.CourierLastKnownEntity
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
 * Connected only while a tracking screen is STARTED (the caller owns
 * `repeatOnLifecycle`-scoping `start`/`stop`); this class only owns the
 * connection and the asymmetry DESIGN.md §9 describes: `order_status`
 * frames are durable truth (funnelled through the same OrderWriter every
 * other channel uses -- not duplicated here), while `courier_position`
 * frames are in-memory-only, throttled to one write per ~15s into
 * `courier_last_known` purely so a cold start shows the marker at roughly
 * the right place instead of jumping from the restaurant.
 */
class TrackingRepository(
    private val wsClient: WebSocketDataSource,
    private val courierLastKnownDao: CourierLastKnownDao,
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
