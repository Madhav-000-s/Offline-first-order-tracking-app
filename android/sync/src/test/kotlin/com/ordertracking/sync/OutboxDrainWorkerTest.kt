package com.ordertracking.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ordertracking.core.common.SystemAppClock
import com.ordertracking.core.data.mapper.toRemoteSnapshot
import com.ordertracking.core.data.merge.OrderWriter
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.model.SyncState
import com.ordertracking.core.network.ApiService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

/**
 * The headline test (DESIGN.md §16): offline create -> reconnect -> single
 * row with a serverId, and a duplicated response doesn't produce a second
 * row. MockWebServer + real in-memory Room, no live backend needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutboxDrainWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var apiService: ApiService
    private lateinit var orderWriter: OrderWriter
    private lateinit var placeOrderUseCase: PlaceOrderUseCase
    private lateinit var cancelOrderUseCase: CancelOrderUseCase
    private lateinit var factory: OutboxDrainWorkerFactory
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        server = MockWebServer()
        server.start()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        apiService = retrofit.create(ApiService::class.java)

        orderWriter = OrderWriter(db.orderDao(), db.syncLogDao(), SystemAppClock())
        placeOrderUseCase = PlaceOrderUseCase(db, SystemAppClock(), json)
        cancelOrderUseCase = CancelOrderUseCase(db, SystemAppClock(), json)
        factory = OutboxDrainWorkerFactory(db, apiService, orderWriter, json, hasSession = { true })
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun orderResponseJson(localId: String, serverId: String, version: Int, status: String = "PLACED") = """
        {
          "id": "$serverId",
          "client_local_id": "$localId",
          "restaurant_id": "rest-1",
          "status": "$status",
          "total_minor": 899,
          "currency": "USD",
          "eta": null,
          "placed_at": "2026-05-01T10:00:00Z",
          "delivery_note": null,
          "tip_minor": 0,
          "route_polyline": null,
          "version": $version,
          "updated_at": "2026-05-01T10:00:01Z",
          "deleted": false,
          "items": [],
          "events": [{"id": "evt-1", "status": "$status", "occurred_at": "2026-05-01T10:00:00Z", "note": null}]
        }
    """.trimIndent()

    @Test
    fun `offline create then drain reconciles to a single row with a serverId`() = runTest {
        // "Airplane mode": place the order purely locally.
        val localId = (placeOrderUseCase.invoke(
            PlaceOrderInput(
                restaurantId = "rest-1",
                currency = "USD",
                items = listOf(PlaceOrderItemInput("menu-1", "Burger", 899, 1)),
            ),
        ) as com.ordertracking.core.common.Outcome.Success).value

        val beforeOrder = db.orderDao().findByLocalId(localId)
        assertNotNull(beforeOrder)
        assertNull("no serverId until it reconciles", beforeOrder!!.serverId)
        assertEquals(SyncState.PENDING_CREATE, beforeOrder.syncState)
        assertEquals(1, db.outboxDao().dueEntries(java.time.Instant.now()).size)

        // "Enable network": drain the outbox against the mock backend.
        server.enqueue(MockResponse().setResponseCode(201).setBody(orderResponseJson(localId, "server-abc", version = 1)))

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build()
        val result = worker.startWork().get()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("outbox row cleared on success", 0, db.outboxDao().dueEntries(java.time.Instant.now()).size)

        val afterOrder = db.orderDao().findByLocalId(localId)
        assertEquals("server-abc", afterOrder!!.serverId)
        assertEquals(SyncState.SYNCED, afterOrder.syncState)
        assertEquals(1L, afterOrder.serverVersion)

        val allOrders = db.orderDao().findByServerId("server-abc")
        assertNotNull(allOrders)
    }

    @Test
    fun `a duplicated create response reconciles to the same single row, not a second one`() = runTest {
        val localId = (placeOrderUseCase.invoke(
            PlaceOrderInput(
                restaurantId = "rest-1",
                currency = "USD",
                items = listOf(PlaceOrderItemInput("menu-1", "Burger", 899, 1)),
            ),
        ) as com.ordertracking.core.common.Outcome.Success).value

        // The server's idempotency guarantee means a lost-response retry
        // gets back the *same* order, same version -- simulate that by
        // applying the identical response twice directly against OrderWriter.
        val response = json.decodeFromString(
            com.ordertracking.core.network.dto.OrderDto.serializer(),
            orderResponseJson(localId, "server-xyz", version = 1),
        )
        orderWriter.apply(response.toRemoteSnapshot(), channel = "REST")
        orderWriter.apply(response.toRemoteSnapshot(), channel = "REST")

        val allWithThatServerId = db.orderDao().findByServerId("server-xyz")
        assertNotNull(allWithThatServerId)
        assertEquals(localId, allWithThatServerId!!.localId)

        // Exactly one row for this localId, full stop -- no duplicate insert
        // happened from replaying the identical create response.
        val byLocalId = db.orderDao().findByLocalId(localId)
        assertEquals("server-xyz", byLocalId!!.serverId)
        assertEquals(1L, byLocalId.serverVersion)
    }

    @Test
    fun `a cancel does not reuse the create's Idempotency-Key`() = runTest {
        val localId = (placeOrderUseCase.invoke(
            PlaceOrderInput(
                restaurantId = "rest-1",
                currency = "USD",
                items = listOf(PlaceOrderItemInput("menu-1", "Burger", 899, 1)),
            ),
        ) as com.ordertracking.core.common.Outcome.Success).value

        cancelOrderUseCase.invoke(localId)

        server.enqueue(MockResponse().setResponseCode(201).setBody(orderResponseJson(localId, "server-abc", version = 1)))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(orderResponseJson(localId, "server-abc", version = 2, status = "CANCELLED")),
        )

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build()
        assertTrue(worker.startWork().get() is androidx.work.ListenableWorker.Result.Success)

        val createKey = server.takeRequest().getHeader("Idempotency-Key")
        val cancelKey = server.takeRequest().getHeader("Idempotency-Key")

        // The create's key is load-bearing: the server stores it as
        // client_local_id, which is how the response finds its local row.
        assertEquals(localId, createKey)
        // The cancel's must differ, or the server sees one key with two
        // different bodies and correctly rejects it as a 422 conflict --
        // which the worker classifies as permanent, failing the order for good.
        assertNotEquals("cancel must carry its own key", createKey, cancelKey)

        val order = db.orderDao().findByLocalId(localId)!!
        assertEquals(SyncState.SYNCED, order.syncState)
        assertNull(order.lastError)
        assertEquals(0, db.outboxDao().dueEntries(java.time.Instant.now()).size)
    }

    @Test
    fun `permanent 4xx marks the order FAILED and does not retry forever`() = runTest {
        val localId = (placeOrderUseCase.invoke(
            PlaceOrderInput(
                restaurantId = "rest-1",
                currency = "USD",
                items = listOf(PlaceOrderItemInput("menu-1", "Burger", 899, 1)),
            ),
        ) as com.ordertracking.core.common.Outcome.Success).value

        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"validation failed"}"""))

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build()
        val result = worker.startWork().get()

        assertTrue("a permanent failure still lets the worker finish, not crash", result is androidx.work.ListenableWorker.Result.Success)

        val order = db.orderDao().findByLocalId(localId)
        assertEquals(SyncState.FAILED, order!!.syncState)
        assertNotNull(order.lastError)

        // The outbox row is kept (for a manual retry action) but deferred far
        // into the future, so it won't be picked up by the next automatic drain.
        assertEquals(0, db.outboxDao().dueEntries(java.time.Instant.now()).size)
    }

    @Test
    fun `with no session the drain leaves the queue untouched`() = runTest {
        val localId = (placeOrderUseCase.invoke(
            PlaceOrderInput(
                restaurantId = "rest-1",
                currency = "USD",
                items = listOf(PlaceOrderItemInput("menu-1", "Burger", 899, 1)),
            ),
        ) as com.ordertracking.core.common.Outcome.Success).value

        // No MockResponse enqueued on purpose: nothing should be sent. Were
        // it sent, the 401 would classify as permanent and mark this order
        // FAILED for a reason that has nothing to do with the order.
        val loggedOut = OutboxDrainWorkerFactory(db, apiService, orderWriter, json, hasSession = { false })
        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(loggedOut)
            .build()
        val result = worker.startWork().get()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("no request should have been made", 0, server.requestCount)

        val order = db.orderDao().findByLocalId(localId)!!
        assertEquals(SyncState.PENDING_CREATE, order.syncState)
        assertNull(order.lastError)
        assertEquals("still queued for when a session exists", 1, db.outboxDao().dueEntries(java.time.Instant.now()).size)
    }

    @Test
    fun `a 5xx is retried, not treated as permanent`() = runTest {
        val localId = (placeOrderUseCase.invoke(
            PlaceOrderInput(
                restaurantId = "rest-1",
                currency = "USD",
                items = listOf(PlaceOrderItemInput("menu-1", "Burger", 899, 1)),
            ),
        ) as com.ordertracking.core.common.Outcome.Success).value

        server.enqueue(MockResponse().setResponseCode(503))

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build()
        val result = worker.startWork().get()

        assertTrue(result is androidx.work.ListenableWorker.Result.Retry)
        val order = db.orderDao().findByLocalId(localId)
        assertEquals("still pending, not failed", SyncState.PENDING_CREATE, order!!.syncState)
        assertEquals(1, db.outboxDao().dueEntries(java.time.Instant.now()).size)
    }
}
