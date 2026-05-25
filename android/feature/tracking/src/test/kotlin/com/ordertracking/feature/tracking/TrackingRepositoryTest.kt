package com.ordertracking.feature.tracking

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.network.ws.WebSocketDataSource
import com.ordertracking.core.network.ws.WsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
            .build()
        fakeWs = FakeWebSocketDataSource()
        gapDetectedFor = null
        repository = TrackingRepository(fakeWs, db.courierLastKnownDao(), onGapDetected = { gapDetectedFor = it })
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
    fun `stop disconnects the socket`() = runTest {
        repository.start(this, accessToken = "token-1", orderId = "server-1")
        repository.stop()
        assertEquals(false, fakeWs.connected)
    }
}
