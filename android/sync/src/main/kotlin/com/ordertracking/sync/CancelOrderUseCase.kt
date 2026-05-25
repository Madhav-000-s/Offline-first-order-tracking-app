package com.ordertracking.sync

import com.ordertracking.core.common.AppClock
import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OutboxEntity
import com.ordertracking.core.network.dto.CancelOrderRequestDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Cancelling is just another outbox-backed mutation -- same machinery as
 * placing an order, which is the payoff of having built the outbox
 * abstraction generically in the first place (DESIGN.md §10 makes the same
 * point about FCM token registration).
 */
class CancelOrderUseCase(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val json: Json,
) {
    suspend fun invoke(orderLocalId: String, reason: String? = null): Outcome<Unit> {
        if (db.orderDao().findByLocalId(orderLocalId) == null) {
            return AppError.Validation("no such local order $orderLocalId").asFailure()
        }

        val outbox = OutboxEntity(
            entityType = "order",
            entityLocalId = orderLocalId,
            operation = "CANCEL",
            payloadJson = json.encodeToString(CancelOrderRequestDto(reason = reason)),
            createdAt = clock.now(),
            nextAttemptAt = clock.now(),
        )
        db.outboxDao().insert(outbox)
        return Unit.asSuccess()
    }
}
