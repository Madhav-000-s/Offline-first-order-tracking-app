package com.ordertracking.core.data.merge

import com.ordertracking.core.common.AppClock
import com.ordertracking.core.data.mapper.toEntity
import com.ordertracking.core.database.dao.OrderDao
import com.ordertracking.core.database.dao.SyncLogDao
import com.ordertracking.core.database.entity.SyncLogEntity

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
