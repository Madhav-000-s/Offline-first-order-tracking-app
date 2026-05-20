package com.ordertracking.core.data.merge

import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit, no Room/Android/network -- exactly the point of separating
 * [MergeEngine.decide] from [OrderWriter] (DESIGN.md §16's testing table).
 */
class MergeEngineTest {

    private val t0 = Instant.parse("2026-05-01T10:00:00Z")

    private fun localOrder(
        status: OrderStatus = OrderStatus.PLACED,
        serverVersion: Long = 3,
        serverId: String? = "server-1",
    ) = OrderEntity(
        localId = "local-1",
        serverId = serverId,
        idempotencyKey = "local-1",
        restaurantId = "rest-1",
        status = status,
        serverVersion = serverVersion,
        placedAtLocal = t0,
        serverUpdatedAt = t0,
        totalMinor = 1000,
        currency = "USD",
        syncState = SyncState.SYNCED,
        lastError = null,
        etaAtServer = null,
        deliveryNote = null,
        tipMinor = 0,
        routePolyline = null,
    )

    private fun remoteSnapshot(
        status: OrderStatus,
        version: Long,
        serverId: String = "server-1",
        clientLocalId: String = "local-1",
    ) = RemoteOrderSnapshot(
        serverId = serverId,
        clientLocalId = clientLocalId,
        restaurantId = "rest-1",
        status = status,
        version = version,
        updatedAt = t0.plusSeconds(version),
        eta = null,
        totalMinor = 1000,
        currency = "USD",
        deliveryNote = null,
        tipMinor = 0,
        routePolyline = null,
        placedAt = t0,
        items = emptyList(),
        events = emptyList(),
    )

    @Test
    fun `no local row is a fresh insert`() {
        val decision = MergeEngine.decide(local = null, remote = remoteSnapshot(OrderStatus.PLACED, version = 1))
        assertTrue(decision is MergeDecision.Insert)
        val entity = (decision as MergeDecision.Insert).entity
        assertEquals("local-1", entity.localId)
        assertEquals(OrderStatus.PLACED, entity.status)
    }

    @Test
    fun `stale version is rejected outright`() {
        val local = localOrder(status = OrderStatus.PREPARING, serverVersion = 7)
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.PICKED_UP, version = 6))
        assertTrue(decision is MergeDecision.RejectStale)
        decision as MergeDecision.RejectStale
        assertEquals(7L, decision.localVersion)
        assertEquals(6L, decision.remoteVersion)
    }

    @Test
    fun `equal version is also rejected as stale, not just lower`() {
        val local = localOrder(status = OrderStatus.PREPARING, serverVersion = 7)
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.READY, version = 7))
        assertTrue(decision is MergeDecision.RejectStale)
    }

    @Test
    fun `status regression is rejected but other fields still advance`() {
        // Local is already at PICKED_UP (ordinal 4); a stale-but-newer-version
        // remote frame claims PREPARING (ordinal 2). The version guard alone
        // would let this through and produce visible status flicker -- the
        // ordinal guard is the seatbelt.
        val local = localOrder(status = OrderStatus.PICKED_UP, serverVersion = 5)
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.PREPARING, version = 6))

        assertTrue(decision is MergeDecision.Update)
        decision as MergeDecision.Update
        assertTrue("regression should be flagged for the sync log", decision.statusRegressionRejected)
        assertEquals("status must stay at the local, further-along value", OrderStatus.PICKED_UP, decision.entity.status)
        assertEquals("version still advances even though status didn't", 6L, decision.entity.serverVersion)
    }

    @Test
    fun `forward status progress is accepted normally`() {
        val local = localOrder(status = OrderStatus.PREPARING, serverVersion = 5)
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.READY, version = 6))

        assertTrue(decision is MergeDecision.Update)
        decision as MergeDecision.Update
        assertTrue(!decision.statusRegressionRejected)
        assertEquals(OrderStatus.READY, decision.entity.status)
    }

    @Test
    fun `CANCELLED beats PICKED_UP despite a lower ordinal`() {
        // CANCELLED has no ordinal position in the ladder at all; terminality
        // is checked before ordinality specifically so this case works.
        val local = localOrder(status = OrderStatus.PICKED_UP, serverVersion = 5)
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.CANCELLED, version = 6))

        assertTrue(decision is MergeDecision.Update)
        decision as MergeDecision.Update
        assertEquals(OrderStatus.CANCELLED, decision.entity.status)
        assertTrue(!decision.statusRegressionRejected)
    }

    @Test
    fun `once terminal, local never leaves that state`() {
        val local = localOrder(status = OrderStatus.DELIVERED, serverVersion = 5)
        // A late/duplicated PICKED_UP frame with a higher version arrives after delivery.
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.PICKED_UP, version = 6))

        assertTrue(decision is MergeDecision.Update)
        decision as MergeDecision.Update
        assertEquals("terminal state must not be reopened", OrderStatus.DELIVERED, decision.entity.status)
        assertTrue(decision.statusRegressionRejected)
    }

    @Test
    fun `merge is idempotent when the same remote frame is applied twice`() {
        val local = localOrder(status = OrderStatus.PREPARING, serverVersion = 5)
        val remote = remoteSnapshot(OrderStatus.READY, version = 6)

        val first = MergeEngine.decide(local, remote) as MergeDecision.Update
        val second = MergeEngine.decide(first.entity, remote)

        // Applying the identical frame again against the now-updated local
        // row must be rejected as stale (version == version), not re-applied.
        assertTrue(second is MergeDecision.RejectStale)
    }

    @Test
    fun `first sync of a serverId still resolves via localId before any serverId is known`() {
        val local = localOrder(status = OrderStatus.PLACED, serverVersion = 0, serverId = null)
        val decision = MergeEngine.decide(local, remoteSnapshot(OrderStatus.ACCEPTED, version = 1, serverId = "server-9"))

        assertTrue(decision is MergeDecision.Update)
        decision as MergeDecision.Update
        assertEquals("server-9", decision.entity.serverId)
        assertEquals(OrderStatus.ACCEPTED, decision.entity.status)
    }
}
