package com.ordertracking.sync

import androidx.room.withTransaction
import com.ordertracking.core.common.AppClock
import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.model.SyncState

/**
 * The other end of [OutboxDrainWorker]'s "deferred, not deleted" policy.
 *
 * When the drain gives up on an entry it keeps the row and pushes
 * `nextAttemptAt` a year out, on the reasoning that silently discarding a
 * user's order because the server said 422 is unacceptable. That only holds
 * up if something can actually bring the entry back -- otherwise "deferred"
 * and "deleted" are the same outcome with different bookkeeping, and the
 * "Failed -- tap to retry" chip is a promise the app can't keep.
 */
class RetryFailedWriteUseCase(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val onOutboxEnqueued: () -> Unit = {},
) {
    suspend fun invoke(orderLocalId: String): Outcome<Unit> {
        val order = db.orderDao().findByLocalId(orderLocalId)
            ?: return AppError.Validation("no such local order $orderLocalId").asFailure()

        // Tapping an order that isn't failed is a no-op rather than an
        // error -- a double tap shouldn't produce a message the user has to
        // think about.
        if (order.syncState != SyncState.FAILED) return Unit.asSuccess()

        val rearmed = db.withTransaction {
            val count = db.outboxDao().rearmForRetry(orderLocalId, clock.now())
            if (count > 0) {
                // Not unconditionally PENDING_CREATE: markPermanentlyFailed
                // stamps FAILED for a failed *cancel* too, and that order
                // was created on the server perfectly well. A serverId is
                // exactly the evidence of which case this is.
                val restored = if (order.serverId == null) SyncState.PENDING_CREATE else SyncState.SYNCED
                db.orderDao().updateOrder(order.copy(syncState = restored, lastError = null))
            }
            count
        }

        if (rearmed == 0) {
            return AppError.Validation("nothing left to retry for this order").asFailure()
        }

        onOutboxEnqueued()
        return Unit.asSuccess()
    }
}
