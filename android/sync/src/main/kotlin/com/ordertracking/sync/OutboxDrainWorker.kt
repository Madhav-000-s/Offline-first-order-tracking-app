package com.ordertracking.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ordertracking.core.data.mapper.toRemoteSnapshot
import com.ordertracking.core.data.merge.OrderWriter
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OutboxEntity
import com.ordertracking.core.model.SyncState
import com.ordertracking.core.network.ApiService
import com.ordertracking.core.network.dto.CancelOrderRequestDto
import com.ordertracking.core.network.dto.DeviceInDto
import com.ordertracking.core.network.dto.PlaceOrderRequestDto
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val MAX_ATTEMPTS = 10

/**
 * The key identifies *this outbox entry*, not the order it refers to.
 *
 * Deriving it from the order's localId alone meant a CREATE and a later
 * CANCEL for the same order sent the identical `Idempotency-Key` with two
 * different request bodies -- which the server correctly reads as a reused
 * key (422), which [classifyFailure] correctly reads as permanent. Net
 * effect: every cancel of an order that had already synced failed for good.
 *
 * Deterministic, not random: the key must stay byte-identical across every
 * retry of the same entry or it stops being an idempotency key at all, so
 * it's derived only from fields that are frozen once the row is written.
 */
private val OutboxEntity.idempotencyKey: String
    get() = if (entityType == "order" && operation == "CREATE") {
        // The CREATE key is load-bearing past idempotency: the server
        // persists it as `client_local_id`, which is how OrderWriter matches
        // the response back to a local row that has no serverId yet.
        entityLocalId
    } else {
        "$entityLocalId:${operation.lowercase()}"
    }

/**
 * Drains the outbox *serially, in insertion order* -- order-cancel must not
 * overtake order-create (DESIGN.md §7 step 6). Retryable failures stop the
 * whole batch and ask WorkManager to retry with backoff, rather than racing
 * ahead past an entry whose relative ordering matters.
 */
class OutboxDrainWorker(
    context: Context,
    params: WorkerParameters,
    private val db: AppDatabase,
    private val apiService: ApiService,
    private val orderWriter: OrderWriter,
    private val json: Json,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outboxDao = db.outboxDao()
        val dueEntries = outboxDao.dueEntries(Instant.now())

        for (entry in dueEntries) {
            val outcome = when (entry.entityType to entry.operation) {
                "order" to "CREATE" -> processCreateOrder(entry)
                "order" to "CANCEL" -> processCancelOrder(entry)
                "fcm_token" to "CREATE" -> processRegisterDevice(entry)
                else -> OutboxOutcome.Permanent("unknown outbox entry type ${entry.entityType}/${entry.operation}")
            }

            when (outcome) {
                is OutboxOutcome.Success -> outboxDao.delete(entry)
                is OutboxOutcome.Retryable -> {
                    // After N attempts (~hours of backoff via WorkManager's
                    // own policy), stop retrying forever and surface it
                    // instead -- a transient failure that never recovers is
                    // indistinguishable from a permanent one to the user.
                    if (entry.attemptCount + 1 >= MAX_ATTEMPTS) {
                        markPermanentlyFailed(entry, "gave up after $MAX_ATTEMPTS attempts")
                    } else {
                        outboxDao.update(entry.copy(attemptCount = entry.attemptCount + 1))
                        return Result.retry()
                    }
                }
                is OutboxOutcome.Permanent -> {
                    markPermanentlyFailed(entry, outcome.message)
                    // Deferred, not deleted: dueEntries() naturally skips it
                    // from here on until a manual retry resets nextAttemptAt.
                }
            }
        }
        return Result.success()
    }

    private suspend fun processCreateOrder(entry: OutboxEntity): OutboxOutcome = try {
        val request = json.decodeFromString<PlaceOrderRequestDto>(entry.payloadJson)
        val response = apiService.placeOrder(idempotencyKey = entry.idempotencyKey, body = request)
        orderWriter.apply(response.toRemoteSnapshot(), channel = "REST")
        OutboxOutcome.Success
    } catch (t: Throwable) {
        classifyFailure(t)
    }

    private suspend fun processCancelOrder(entry: OutboxEntity): OutboxOutcome = try {
        val order = db.orderDao().findByLocalId(entry.entityLocalId)
        val serverId = order?.serverId
        if (serverId == null) {
            // The create for this order hasn't reconciled yet; cancel has
            // nothing to target server-side. Retry -- the create ahead of
            // it in the queue should resolve this on the next drain.
            OutboxOutcome.Retryable
        } else {
            val response = apiService.cancelOrder(
                orderId = serverId,
                idempotencyKey = entry.idempotencyKey,
                body = json.decodeFromString<CancelOrderRequestDto>(entry.payloadJson),
            )
            orderWriter.apply(response.toRemoteSnapshot(), channel = "REST")
            OutboxOutcome.Success
        }
    } catch (t: Throwable) {
        classifyFailure(t)
    }

    private suspend fun processRegisterDevice(entry: OutboxEntity): OutboxOutcome = try {
        apiService.registerDevice(json.decodeFromString<DeviceInDto>(entry.payloadJson))
        OutboxOutcome.Success
    } catch (t: Throwable) {
        classifyFailure(t)
    }

    private suspend fun markPermanentlyFailed(entry: OutboxEntity, message: String) {
        db.withTransaction {
            db.orderDao().findByLocalId(entry.entityLocalId)?.let { order ->
                db.orderDao().updateOrder(order.copy(syncState = SyncState.FAILED, lastError = message))
            }
            db.outboxDao().update(
                entry.copy(
                    attemptCount = entry.attemptCount + 1,
                    lastError = message,
                    nextAttemptAt = Instant.now().plus(Duration.ofDays(365)),
                ),
            )
        }
    }

    companion object {
        const val WORK_NAME = "outbox_drain"
    }
}

class OutboxDrainWorkerFactory(
    private val db: AppDatabase,
    private val apiService: ApiService,
    private val orderWriter: OrderWriter,
    private val json: Json,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == OutboxDrainWorker::class.java.name) {
        OutboxDrainWorker(appContext, workerParameters, db, apiService, orderWriter, json)
    } else {
        null
    }
}
