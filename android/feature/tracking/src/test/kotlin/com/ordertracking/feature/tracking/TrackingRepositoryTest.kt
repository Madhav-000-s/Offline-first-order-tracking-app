package com.ordertracking.feature.tracking

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ordertracking.core.common.SystemAppClock
import com.ordertracking.core.data.merge.OrderWriter
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import com.ordertracking.core.network.ws.WebSocketDataSource
import com.ordertracking.core.network.ws.WsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeWebSocketDataSource : WebSocketDataSource {
    val emitted = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<WsEvent> = emitted
    var connected = false
    var subscribedOrderId: String? = null

    override fun connect(accessToken: String) { connected = true }
    override fun subscribe(orderId: String) { subscribedOrderId = orderId }
    override fun ping() = Unit
    override fun disconnect() { connected = false }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrackingRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var fakeWs: FakeWebSocketDataSource
    private var gapDetectedFor: String? = null
    private lateinit var repository: TrackingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            // Inline executors, so a suspending DAO call resumes on the
            // calling thread instead of hopping to Room's own pool. Without
            // this, `advanceUntilIdle()` only drains the test scheduler and
            // returns while a write dispatched into Room's executor is still
            // in flight -- the assertion then races the database.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        fakeWs = FakeWebSocketDataSource()
        gapDetectedFor = null
        repository = TrackingRepository(
            wsClient = fakeWs,
            courierLastKnownDao = db.courierLastKnownDao(),
            orderWriter = OrderWriter(db.orderDao(), db.syncLogDao(), SystemAppClock()),
            onGapDetected = { gapDetectedFor = it },
        )
    }

    private suspend fun seedSyncedOrder(serverId: String, status: OrderStatus, version: Long) {
        db.orderDao().upsertOrder(
            OrderEntity(
                localId = "local-1",
                serverId = serverId,
                idempotencyKey = "local-1",
                restaurantId = "r-1",
                status = status,
                serverVersion = version,
                placedAtLocal = Instant.parse("2024-01-01T00:00:00Z"),
                serverUpdatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                totalMinor = 1000,
                currency = "INR",
                syncState = SyncState.SYNCED,
                lastError = null,
                etaAtServer = null,
                deliveryNote = null,
                tipMinor = 0,
                routePolyline = null,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `start connects, subscribes, and forwards position updates for the watched order only`() = runTest {
        repository.start(this, accessToken = "token-1", orderId = "server-1")
        assertEquals(true, fakeWs.connected)
        assertEquals("server-1", fakeWs.subscribedOrderId)

        // Let the coroutine launched by start() actually reach `collect` and
        // subscribe before emitting -- a SharedFlow with no active collector
        // yet just drops the emission rather than buffering it for latecomers.
        advanceUntilIdle()

        fakeWs.emitted.emit(
            WsEvent.CourierPosition(orderId = "server-1", lat = 12.9, lng = 77.6, bearing = 90f, speedMps = 6.0, seq = 1),
        )
        advanceUntilIdle()
        assertEquals(12.9, repository.courierPosition.value!!.lat, 1e-9)

        // A position for a *different* order must not overwrite this screen's state.
        fakeWs.emitted.emit(
            WsEvent.CourierPosition(orderId = "some-other-order", lat = 0.0, lng = 0.0, bearing = 0f, speedMps = 0.0, seq = 1),
        )
        advanceUntilIdle()
        assertEquals(12.9, repository.courierPosition.value!!.lat, 1e-9)

        // `collect` on a SharedFlow never completes on its own; runTest
        // requires every coroutine it launched to finish or be cancelled
        // before the test body returns, so this has to happen in-scope,
        // not in @After (that runs after runTest's own check already ran).
        repository.stop()
    }

    @Test
    fun `gap detection callback fires only for the watched order`() = runTest {
        repository.start(this, accessToken = "token-1", orderId = "server-1")
        advanceUntilIdle()

        fakeWs.emitted.emit(WsEvent.GapDetected(orderId = "server-1", expected = 5, actual = 8))
        advanceUntilIdle()
        assertEquals("server-1", gapDetectedFor)

        gapDetectedFor = null
        fakeWs.emitted.emit(WsEvent.GapDetected(orderId = "unrelated-order", expected = 1, actual = 2))
        advanceUntilIdle()
        assertNull(gapDetectedFor)

        repository.stop()
    }

    @Test
    fun `an order_status frame lands in Room through the merge engine`() = runTest {
        seedSyncedOrder(serverId = "server-1", status = OrderStatus.PREPARING, version = 4)
        repository.start(this, accessToken = "token-1", orderId = "server-1")
        advanceUntilIdle()

        fakeWs.emitted.emit(
            WsEvent.OrderStatus(orderId = "server-1", version = 5, status = "READY", seq = 2),
        )
        advanceUntilIdle()

        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals(OrderStatus.READY, row.status)
        assertEquals(5L, row.serverVersion)

        repository.stop()
    }

    @Test
    fun `a stale order_status frame is rejected, not applied`() = runTest {
        seedSyncedOrder(serverId = "server-1", status = OrderStatus.PICKED_UP, version = 9)
        repository.start(this, accessToken = "token-1", orderId = "server-1")
        advanceUntilIdle()

        // A slow frame overtaken by a delta sync that already advanced the row.
        fakeWs.emitted.emit(
            WsEvent.OrderStatus(orderId = "server-1", version = 7, status = "PREPARING", seq = 3),
        )
        advanceUntilIdle()

        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals(OrderStatus.PICKED_UP, row.status)
        assertEquals(9L, row.serverVersion)

        repository.stop()
    }

    @Test
    fun `stop disconnects the socket`() = runTest {
        repository.start(this, accessToken = "token-1", orderId = "server-1")
        repository.stop()
        assertEquals(false, fakeWs.connected)
    }
}
