package com.ordertracking.core.data.merge

import com.ordertracking.core.common.AppClock
import com.ordertracking.core.data.mapper.toEntity
import com.ordertracking.core.database.dao.OrderDao
import com.ordertracking.core.database.dao.SyncLogDao
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.SyncLogEntity
import com.ordertracking.core.model.OrderStatus

/**
 * The only class in the app permitted to INSERT/UPDATE the `orders` table
 * from remote data. REST responses, WS frames, and FCM payloads all end up
 * calling [apply] -- this is what makes the three-channel convergence
 * tractable: there is exactly one place where "is this update stale?" is
 * decided (DESIGN.md §3).
 */
class OrderWriter(
    private val orderDao: OrderDao,
    private val syncLogDao: SyncLogDao,
    private val clock: AppClock,
) {

    suspend fun apply(remote: RemoteOrderSnapshot, channel: String): MergeDecision {
        // serverId first: WS/FCM/subsequent-sync updates only ever carry the
        // server id. localId is the fallback for the first reconciliation of
        // an order this device created offline, before it has a serverId yet.
        val local = orderDao.findByServerId(remote.serverId) ?: orderDao.findByLocalId(remote.clientLocalId)
        val decision = MergeEngine.decide(local, remote)

        when (decision) {
            is MergeDecision.Insert -> {
                orderDao.upsertOrder(decision.entity)
                orderDao.upsertItems(remote.items.map { it.toEntity() })
                orderDao.upsertEvents(remote.events.map { it.toEntity() })
                log(decision.entity.localId, channel, "INSERT", "new order, version=${remote.version}")
            }
            is MergeDecision.Update -> {
                orderDao.updateOrder(decision.entity)
                // PK is the server event id (or "local:$uuid"); re-inserting
                // an id we already have is a harmless REPLACE, so it's safe
                // to just upsert the full remote event list unconditionally.
                orderDao.upsertEvents(remote.events.map { it.toEntity() })
                if (decision.statusRegressionRejected) {
                    log(
                        decision.entity.localId,
                        channel,
                        "REJECT_REGRESSION",
                        "remote status ${remote.status} rejected (regression); kept ${decision.entity.status}, version=${remote.version}",
                    )
                } else {
                    log(
                        decision.entity.localId,
                        channel,
                        "ACCEPT",
                        "applied version=${remote.version} status=${decision.entity.status}",
                    )
                }
            }
            is MergeDecision.RejectStale -> {
                log(
                    remote.clientLocalId,
                    channel,
                    "REJECT_STALE",
                    "remote version=${decision.remoteVersion} <= local version=${decision.localVersion}",
                )
            }
        }
        return decision
    }

    /**
     * The WebSocket/FCM path (DESIGN.md §9). A live frame carries only
     * `order_id`, `version` and `status` -- no restaurant, no items, no
     * totals -- so it cannot build a [RemoteOrderSnapshot] on its own and
     * cannot create a row. It resolves the local row by serverId, overlays
     * the two fields it actually knows, and then goes through [apply] like
     * everything else, so the version and FSM guards are literally the same
     * code rather than a second implementation that can drift.
     *
     * Returns null when this device has no such order -- a frame for an
     * order we've never synced isn't an error, it just means the delta sync
     * hasn't reached it yet, and the caller's gap/sync trigger will.
     */
    suspend fun applyStatus(serverId: String, version: Long, status: OrderStatus, channel: String): MergeDecision? {
        val local = orderDao.findByServerId(serverId)
        if (local == null) {
            log(serverId, channel, "SKIP_UNKNOWN", "status frame for an order not in the local cache")
            return null
        }
        return apply(local.toStatusOverlay(version, status), channel)
    }

    /**
     * Every field except the two the frame carries is echoed straight back
     * from the local row, so the merge is a no-op on them. Notably
     * `serverUpdatedAt` is *not* set to the device clock -- a live frame
     * doesn't tell us the server's updated_at, and inventing one would put
     * a client timestamp into a server-owned column.
     */
    private fun OrderEntity.toStatusOverlay(version: Long, status: OrderStatus) = RemoteOrderSnapshot(
        serverId = requireNotNull(serverId),
        clientLocalId = localId,
        restaurantId = restaurantId,
        status = status,
        version = version,
        updatedAt = serverUpdatedAt ?: placedAtLocal,
        eta = etaAtServer,
        totalMinor = totalMinor,
        currency = currency,
        deliveryNote = deliveryNote,
        tipMinor = tipMinor,
        routePolyline = routePolyline,
        placedAt = placedAtLocal,
        items = emptyList(),
        events = emptyList(),
    )

    private suspend fun log(orderLocalId: String, channel: String, decision: String, detail: String) {
        syncLogDao.record(
            SyncLogEntity(
                occurredAt = clock.now(),
                orderLocalId = orderLocalId,
                channel = channel,
                decision = decision,
                detail = detail,
            ),
        )
    }
}
