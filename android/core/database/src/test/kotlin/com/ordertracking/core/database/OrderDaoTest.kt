package com.ordertracking.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.OrderEventEntity
import com.ordertracking.core.database.entity.OrderItemEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OrderDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun sampleOrder(localId: String = "local-1") = OrderEntity(
        localId = localId,
        serverId = null,
        idempotencyKey = localId,
        restaurantId = "rest-1",
        status = OrderStatus.PLACED,
        serverVersion = 0,
        placedAtLocal = Instant.parse("2026-05-01T10:00:00Z"),
        serverUpdatedAt = null,
        totalMinor = 1299,
        currency = "USD",
        syncState = SyncState.PENDING_CREATE,
        lastError = null,
        etaAtServer = null,
        deliveryNote = null,
        tipMinor = 0,
        routePolyline = null,
    )

    @Test
    fun `Flow emits on insert from another coroutine`() = runTest(UnconfinedTestDispatcher()) {
        db.orderDao().observeOrders().test {
            assertTrue("expected no orders yet", awaitItem().isEmpty())

            // Insert from a separately-launched coroutine -- the whole point
            // of this test is proving the Flow notices writes it didn't
            // itself initiate, which is what "the UI has no idea the
            // network exists" actually depends on at runtime.
            CoroutineScope(Dispatchers.Unconfined).launch {
                db.orderDao().insertNewOrder(
                    order = sampleOrder(),
                    items = listOf(
                        OrderItemEntity(
                            id = "item-1",
                            orderLocalId = "local-1",
                            menuItemId = "menu-1",
                            nameSnapshot = "Burger",
                            unitPriceMinor = 1299,
                            quantity = 1,
                        ),
                    ),
                    event = OrderEventEntity(
                        id = "local:evt-1",
                        orderLocalId = "local-1",
                        status = OrderStatus.PLACED,
                        occurredAt = Instant.parse("2026-05-01T10:00:00Z"),
                        note = null,
                    ),
                )
            }

            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("local-1", updated[0].order.localId)
            assertEquals(1, updated[0].items.size)
            assertEquals(1, updated[0].events.size)
        }
    }

    @Test
    fun `serverId is nullable and non-unique across never-synced rows`() = runTest(UnconfinedTestDispatcher()) {
        db.orderDao().upsertOrder(sampleOrder(localId = "local-a"))
        db.orderDao().upsertOrder(sampleOrder(localId = "local-b"))

        db.orderDao().observeOrders().test {
            assertEquals(2, awaitItem().size)
        }
    }
}
