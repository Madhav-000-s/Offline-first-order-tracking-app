package com.ordertracking.feature.orders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.ordertracking.core.data.repository.OrderRepository
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OrdersListViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: OrderRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OrderRepository(db.orderDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun entity(localId: String) = OrderEntity(
        localId = localId,
        serverId = null,
        idempotencyKey = localId,
        restaurantId = "rest-1",
        status = OrderStatus.PLACED,
        serverVersion = 0,
        placedAtLocal = Instant.parse("2026-05-01T10:00:00Z"),
        serverUpdatedAt = null,
        totalMinor = 500,
        currency = "USD",
        syncState = SyncState.PENDING_CREATE,
        lastError = null,
        etaAtServer = null,
        deliveryNote = null,
        tipMinor = 0,
        routePolyline = null,
    )

    @Test
    fun `state reflects Room as orders are inserted, with no explicit refresh`() = runTest(dispatcher) {
        var refreshCalled = false
        val viewModel = OrdersListViewModel(repository, onPullToRefresh = { refreshCalled = true })

        viewModel.uiState.test {
            assertEquals(emptyList<Any>(), awaitItem().orders)

            db.orderDao().upsertOrder(entity("local-1"))
            assertEquals(1, awaitItem().orders.size)

            db.orderDao().upsertOrder(entity("local-2"))
            assertEquals(2, awaitItem().orders.size)
        }

        viewModel.onIntent(OrdersListIntent.PullToRefresh)
        assertEquals("pull-to-refresh delegates to the injected trigger, not a direct network call", true, refreshCalled)
    }

    @Test
    fun `clicking an order emits a navigate effect with its localId`() = runTest(dispatcher) {
        val viewModel = OrdersListViewModel(repository, onPullToRefresh = {})

        viewModel.effects.test {
            viewModel.onIntent(OrdersListIntent.OrderClicked("local-42"))
            val effect = awaitItem()
            assertEquals(OrdersListEffect.NavigateToDetail("local-42"), effect)
        }
    }
}
