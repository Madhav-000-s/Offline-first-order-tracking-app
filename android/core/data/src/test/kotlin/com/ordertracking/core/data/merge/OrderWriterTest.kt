package com.ordertracking.core.data.merge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ordertracking.core.common.SystemAppClock
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MergeEngineTest] covers the decision itself as pure logic. This covers
 * the shell around it -- specifically [OrderWriter.applyStatus], the
 * WS/FCM path, which has to reach the *same* guards from a frame carrying
 * only order_id + version + status (DESIGN.md §9).
 */
@RunWith(RobolectricTestRunner::class)
class OrderWriterTest {

    private lateinit var db: AppDatabase
    private lateinit var writer: OrderWriter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = OrderWriter(db.orderDao(), db.syncLogDao(), SystemAppClock())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(status: OrderStatus, version: Long, syncState: SyncState = SyncState.SYNCED) {
        db.orderDao().upsertOrder(
            OrderEntity(
                localId = "local-1",
                serverId = "server-1",
                idempotencyKey = "local-1",
                restaurantId = "r-1",
                status = status,
                serverVersion = version,
                placedAtLocal = Instant.parse("2024-01-01T00:00:00Z"),
                serverUpdatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                totalMinor = 2500,
                currency = "INR",
                syncState = syncState,
                lastError = null,
                etaAtServer = null,
                deliveryNote = "leave at the gate",
                tipMinor = 300,
                routePolyline = "abc",
            ),
        )
    }

    @Test
    fun `a forward status frame is applied`() = runTest {
        seed(OrderStatus.PREPARING, version = 3)

        val decision = writer.applyStatus("server-1", version = 4, status = OrderStatus.READY, channel = "WS")

        assertTrue(decision is MergeDecision.Update)
        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals(OrderStatus.READY, row.status)
        assertEquals(4L, row.serverVersion)
    }

    @Test
    fun `a stale version is rejected by the same guard REST uses`() = runTest {
        seed(OrderStatus.PICKED_UP, version = 9)

        val decision = writer.applyStatus("server-1", version = 9, status = OrderStatus.PREPARING, channel = "WS")

        assertTrue(decision is MergeDecision.RejectStale)
        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals(OrderStatus.PICKED_UP, row.status)
        assertEquals(9L, row.serverVersion)
    }

    @Test
    fun `a status regression on a higher version is held at the local status`() = runTest {
        seed(OrderStatus.PICKED_UP, version = 5)

        // Higher version, so the version guard lets it through -- the FSM
        // guard is the only thing standing between this and visible flicker.
        writer.applyStatus("server-1", version = 6, status = OrderStatus.PREPARING, channel = "WS")

        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals(OrderStatus.PICKED_UP, row.status)
        assertEquals(6L, row.serverVersion)
    }

    @Test
    fun `fields the frame does not carry survive untouched`() = runTest {
        seed(OrderStatus.ACCEPTED, version = 2)

        writer.applyStatus("server-1", version = 3, status = OrderStatus.PREPARING, channel = "WS")

        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals("leave at the gate", row.deliveryNote)
        assertEquals(300L, row.tipMinor)
        assertEquals(2500L, row.totalMinor)
        assertEquals("abc", row.routePolyline)
        // Never stamped with the device clock: a live frame doesn't tell us
        // the server's updated_at, so the column keeps its last known value.
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), row.serverUpdatedAt)
    }

    @Test
    fun `a frame for an unknown order writes nothing and is logged`() = runTest {
        val decision = writer.applyStatus("never-seen", version = 1, status = OrderStatus.READY, channel = "WS")

        assertNull(decision)
        assertNull(db.orderDao().findByServerId("never-seen"))
    }

    @Test
    fun `applying the same frame twice is idempotent`() = runTest {
        seed(OrderStatus.PREPARING, version = 3)

        writer.applyStatus("server-1", version = 4, status = OrderStatus.READY, channel = "WS")
        val second = writer.applyStatus("server-1", version = 4, status = OrderStatus.READY, channel = "WS")

        assertTrue(second is MergeDecision.RejectStale)
        val row = db.orderDao().findByServerId("server-1")!!
        assertEquals(OrderStatus.READY, row.status)
        assertEquals(4L, row.serverVersion)
    }
}
