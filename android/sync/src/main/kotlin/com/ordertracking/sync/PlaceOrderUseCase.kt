package com.ordertracking.sync

import androidx.room.withTransaction
import com.ordertracking.core.common.AppClock
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.OrderEventEntity
import com.ordertracking.core.database.entity.OrderItemEntity
import com.ordertracking.core.database.entity.OutboxEntity
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.SyncState
import com.ordertracking.core.network.dto.OrderItemInDto
import com.ordertracking.core.network.dto.PlaceOrderRequestDto
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PlaceOrderItemInput(val menuItemId: String, val nameSnapshot: String, val unitPriceMinor: Long, val quantity: Int)

data class PlaceOrderInput(
    val restaurantId: String,
    val currency: String,
    val items: List<PlaceOrderItemInput>,
    val deliveryNote: String? = null,
    val tipMinor: Long = 0,
)

/**
 * Placing an order offline, in one Room transaction (DESIGN.md §7 step 2):
 * insert `orders` (syncState = PENDING_CREATE, serverVersion = 0, status =
 * PLACED), insert `order_items`, insert a local `order_events` row, insert
 * an `outbox` row with the serialised request. Transactional atomicity
 * means we can never have an order without its outbox entry, or vice versa.
 * The DAO Flow emits as soon as this returns -- the order appears in the
 * list instantly, badged "Waiting to send", with nobody ever telling a
 * screen to refresh.
 */
class PlaceOrderUseCase(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val json: Json,
) {
    suspend fun invoke(input: PlaceOrderInput): Outcome<String> {
        val localId = UUID.randomUUID().toString()
        val now = clock.now()
        val totalMinor = input.items.sumOf { it.unitPriceMinor * it.quantity }

        val order = OrderEntity(
            localId = localId,
            serverId = null,
            idempotencyKey = localId,
            restaurantId = input.restaurantId,
            status = OrderStatus.PLACED,
            serverVersion = 0,
            placedAtLocal = now,
            serverUpdatedAt = null,
            totalMinor = totalMinor,
            currency = input.currency,
            syncState = SyncState.PENDING_CREATE,
            lastError = null,
            etaAtServer = null,
            deliveryNote = input.deliveryNote,
            tipMinor = input.tipMinor,
            routePolyline = null,
        )
        val items = input.items.map {
            OrderItemEntity(
                id = UUID.randomUUID().toString(),
                orderLocalId = localId,
                menuItemId = it.menuItemId,
                nameSnapshot = it.nameSnapshot,
                unitPriceMinor = it.unitPriceMinor,
                quantity = it.quantity,
            )
        }
        val event = OrderEventEntity(
            id = "local:${UUID.randomUUID()}",
            orderLocalId = localId,
            status = OrderStatus.PLACED,
            occurredAt = now,
            note = null,
        )

        val requestBody = PlaceOrderRequestDto(
            restaurant_id = input.restaurantId,
            items = input.items.map { OrderItemInDto(menu_item_id = it.menuItemId, quantity = it.quantity) },
            delivery_note = input.deliveryNote,
            tip_minor = input.tipMinor,
        )
        val outbox = OutboxEntity(
            entityType = "order",
            entityLocalId = localId,
            operation = "CREATE",
            payloadJson = json.encodeToString(requestBody),
            createdAt = now,
            nextAttemptAt = now,
        )

        db.withTransaction {
            db.orderDao().insertNewOrder(order, items, event)
            db.outboxDao().insert(outbox)
        }

        return localId.asSuccess()
    }
}
