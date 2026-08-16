package com.ordertracking.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.SystemAppClock
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.OutboxEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The counterpart to [OutboxDrainWorkerTest]'s "permanent 4xx" case. That
 * test asserts the entry is *kept* rather than deleted; these assert that
 * keeping it is actually worth something.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RetryFailedWriteUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var useCase: RetryFailedWriteUseCase
    private var drainRequested = 0

    private val now: Instant = Instant.parse("2026-05-01T10:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        useCase = RetryFailedWriteUseCase(db, SystemAppClock()) { drainRequested++ }
    }

    @After
    fun tearDown() = db.close()

    private fun failedOrder(localId: String, serverId: String? = null) = OrderEntity(
        localId = localId,
        serverId = serverId,
        idempotencyKey = localId,
        restaurantId = "rest-1",
        status = OrderStatus.PLACED,
        serverVersion = 0,
        placedAtLocal = now,
        serverUpdatedAt = null,
        totalMinor = 899,
        currency = "USD",
        syncState = SyncState.FAILED,
        lastError = "HTTP 422: validation failed",
        etaAtServer = null,
        deliveryNote = null,
        tipMinor = 0,
        routePolyline = null,
    )

    /** Mirrors what markPermanentlyFailed() leaves behind: kept, but deferred a year out. */
    private fun deferredEntry(localId: String, operation: String = "CREATE") = OutboxEntity(
        entityType = "order",
        entityLocalId = localId,
        operation = operation,
        payloadJson = "{}",
        createdAt = now,
        attemptCount = 1,
        nextAttemptAt = now.plus(Duration.ofDays(365)),
        lastError = "HTTP 422: validation failed",
    )

    @Test
    fun `retry brings a deferred entry back into the drain's due set`() = runTest {
        db.orderDao().upsertOrder(failedOrder("local-1"))
        db.outboxDao().insert(deferredEntry("local-1"))

        assertEquals("precondition: deferred out of reach", 0, db.outboxDao().dueEntries(Instant.now()).size)

        val outcome = useCase.invoke("local-1")

        assertTrue(outcome is Outcome.Success)
        assertEquals(1, db.outboxDao().dueEntries(Instant.now()).size)
        assertEquals("the drain has to actually be asked to run", 1, drainRequested)
    }

    @Test
    fun `retry resets attemptCount so the drain does not give up immediately`() = runTest {
        db.orderDao().upsertOrder(failedOrder("local-1"))
        db.outboxDao().insert(deferredEntry("local-1").copy(attemptCount = 9))

        useCase.invoke("local-1")

        val entry = db.outboxDao().dueEntries(Instant.now()).single()
        assertEquals(0, entry.attemptCount)
        assertNull(entry.lastError)
    }

    @Test
    fun `a failed create goes back to PENDING_CREATE`() = runTest {
        db.orderDao().upsertOrder(failedOrder("local-1", serverId = null))
        db.outboxDao().insert(deferredEntry("local-1", operation = "CREATE"))

        useCase.invoke("local-1")

        val order = db.orderDao().findByLocalId("local-1")!!
        assertEquals(SyncState.PENDING_CREATE, order.syncState)
        assertNull(order.lastError)
    }

    @Test
    fun `a failed cancel goes back to SYNCED, not PENDING_CREATE`() = runTest {
        // The order reached the server perfectly well; it was the cancel that
        // failed. Calling it PENDING_CREATE would badge a synced order
        // "Waiting to send" and misreport what is actually outstanding.
        db.orderDao().upsertOrder(failedOrder("local-1", serverId = "srv-1"))
        db.outboxDao().insert(deferredEntry("local-1", operation = "CANCEL"))

        useCase.invoke("local-1")

        assertEquals(SyncState.SYNCED, db.orderDao().findByLocalId("local-1")!!.syncState)
    }

    @Test
    fun `retrying an order that is not failed is a no-op, not an error`() = runTest {
        db.orderDao().upsertOrder(failedOrder("local-1").copy(syncState = SyncState.SYNCED, lastError = null))

        val outcome = useCase.invoke("local-1")

        assertTrue("a double tap shouldn't surface a message", outcome is Outcome.Success)
        assertEquals("nothing to send, so nothing to schedule", 0, drainRequested)
    }

    @Test
    fun `retrying an unknown order fails`() = runTest {
        val outcome = useCase.invoke("nope")

        assertTrue(outcome is Outcome.Failure)
        assertEquals(0, drainRequested)
    }

    @Test
    fun `a failed order whose outbox entry is gone reports that rather than silently clearing`() = runTest {
        db.orderDao().upsertOrder(failedOrder("local-1"))
        // No outbox row: there is genuinely nothing to resend, so flipping
        // the badge to "Waiting to send" would be a lie.

        val outcome = useCase.invoke("local-1")

        assertTrue(outcome is Outcome.Failure)
        assertEquals(SyncState.FAILED, db.orderDao().findByLocalId("local-1")!!.syncState)
        assertEquals(0, drainRequested)
    }
}
