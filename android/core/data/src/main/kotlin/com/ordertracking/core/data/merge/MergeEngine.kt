package com.ordertracking.core.data.merge

import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.model.SyncState

sealed interface MergeDecision {
    data class Insert(val entity: OrderEntity) : MergeDecision

    /**
     * [statusRegressionRejected] is true when every other field advanced but
     * the status itself was held at the local value because the remote
     * status would have been a regression -- still a "reject", still worth
     * a sync_log row, just not a full RejectStale (DESIGN.md §6).
     */
    data class Update(val entity: OrderEntity, val statusRegressionRejected: Boolean) : MergeDecision

    data class RejectStale(val localVersion: Long, val remoteVersion: Long) : MergeDecision
}

/**
 * The single-writer merge decision (DESIGN.md §6). Pure function: no Room,
 * no network, no coroutines -- given local + remote, what should the row
 * become. [OrderWriter] is the thin shell that actually applies this to
 * Room and records the decision to sync_log.
 *
 * Three channels (REST/WS/FCM) deliver the same fact at different
 * latencies with no ordering guarantee between them. A naive last-write-wins
 * produces visible status flicker when a slow REST response lands after a
 * fast WS frame -- this exploits the one property the FSM actually
 * guarantees: order status is a position in a monotonic ladder, not an
 * opaque value.
 */
object MergeEngine {

    fun decide(local: OrderEntity?, remote: RemoteOrderSnapshot): MergeDecision {
        if (local == null) {
            return MergeDecision.Insert(remote.toFreshEntity())
        }

        // 1. Version guard -- the primary defence. Server version is monotonic per row.
        if (remote.version <= local.serverVersion) {
            return MergeDecision.RejectStale(localVersion = local.serverVersion, remoteVersion = remote.version)
        }

        // 2. Status guard -- defence in depth against a server bug or a replayed event.
        val remoteIsTerminal = remote.status.isTerminal
        val localIsTerminal = local.status.isTerminal
        val nextStatus = when {
            remoteIsTerminal -> remote.status // terminal always wins
            localIsTerminal -> local.status // never leave a terminal state
            remote.status.ordinal >= local.status.ordinal -> remote.status
            else -> local.status // reject regression, log it
        }
        val regressionRejected = nextStatus == local.status && nextStatus != remote.status

        // 3. Client-owned fields use LWW on serverUpdatedAt, because the
        // server just echoes back whatever the outbox last pushed.
        val updated = local.copy(
            serverId = remote.serverId,
            status = nextStatus,
            serverVersion = remote.version,
            serverUpdatedAt = remote.updatedAt,
            etaAtServer = remote.eta,
            totalMinor = remote.totalMinor,
            currency = remote.currency,
            deliveryNote = remote.deliveryNote,
            tipMinor = remote.tipMinor,
            routePolyline = remote.routePolyline ?: local.routePolyline,
            syncState = SyncState.SYNCED,
            lastError = null,
        )
        return MergeDecision.Update(updated, regressionRejected)
    }

    private fun RemoteOrderSnapshot.toFreshEntity(): OrderEntity = OrderEntity(
        localId = clientLocalId,
        serverId = serverId,
        idempotencyKey = clientLocalId,
        restaurantId = restaurantId,
        status = status,
        serverVersion = version,
        placedAtLocal = placedAt,
        serverUpdatedAt = updatedAt,
        totalMinor = totalMinor,
        currency = currency,
        syncState = SyncState.SYNCED,
        lastError = null,
        etaAtServer = eta,
        deliveryNote = deliveryNote,
        tipMinor = tipMinor,
        routePolyline = routePolyline,
    )
}
