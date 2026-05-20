package com.ordertracking.core.data.mapper

import com.ordertracking.core.data.merge.RemoteOrderSnapshot
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.OrderEventEntity
import com.ordertracking.core.database.entity.OrderItemEntity
import com.ordertracking.core.database.entity.OrderWithDetails
import com.ordertracking.core.model.Order
import com.ordertracking.core.model.OrderEvent
import com.ordertracking.core.model.OrderItem
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.network.dto.OrderDto
import java.time.Instant

fun OrderWithDetails.toDomain(): Order = Order(
    localId = order.localId,
    serverId = order.serverId,
    restaurantId = order.restaurantId,
    status = order.status,
    serverVersion = order.serverVersion,
    placedAtLocal = order.placedAtLocal,
    serverUpdatedAt = order.serverUpdatedAt,
    totalMinor = order.totalMinor,
    currency = order.currency,
    syncState = order.syncState,
    lastError = order.lastError,
    etaAtServer = order.etaAtServer,
    deliveryNote = order.deliveryNote,
    tipMinor = order.tipMinor,
    routePolyline = order.routePolyline,
    items = items.map { it.toDomain() },
    events = events.map { it.toDomain() }.sortedBy { it.occurredAt },
)

fun OrderItemEntity.toDomain(): OrderItem = OrderItem(
    id = id,
    orderLocalId = orderLocalId,
    menuItemId = menuItemId,
    nameSnapshot = nameSnapshot,
    unitPriceMinor = unitPriceMinor,
    quantity = quantity,
)

fun OrderEventEntity.toDomain(): OrderEvent = OrderEvent(
    id = id,
    orderLocalId = orderLocalId,
    status = status,
    occurredAt = occurredAt,
    note = note,
)

fun parseOrderStatus(raw: String): OrderStatus = OrderStatus.valueOf(raw)

fun parseInstant(raw: String): Instant = Instant.parse(raw)

fun OrderItem.toEntity(): OrderItemEntity = OrderItemEntity(
    id = id,
    orderLocalId = orderLocalId,
    menuItemId = menuItemId,
    nameSnapshot = nameSnapshot,
    unitPriceMinor = unitPriceMinor,
    quantity = quantity,
)

fun OrderEvent.toEntity(): OrderEventEntity = OrderEventEntity(
    id = id,
    orderLocalId = orderLocalId,
    status = status,
    occurredAt = occurredAt,
    note = note,
)

/**
 * The REST/full-sync path: every field the wire format carries is present,
 * so [RemoteOrderSnapshot.clientLocalId] is always the real one. WS partial
 * updates (order_id + status only, no client_local_id) can't use this
 * directly -- that path resolves the local row by serverId first and
 * builds a snapshot from the existing local row's other fields plus the
 * WS delta (:feature:tracking, once that repository exists).
 */
fun OrderDto.toRemoteSnapshot(): RemoteOrderSnapshot = RemoteOrderSnapshot(
    serverId = id,
    clientLocalId = client_local_id,
    restaurantId = restaurant_id,
    status = parseOrderStatus(status),
    version = version,
    updatedAt = parseInstant(updated_at),
    eta = eta?.let(::parseInstant),
    totalMinor = total_minor,
    currency = currency,
    deliveryNote = delivery_note,
    tipMinor = tip_minor,
    routePolyline = route_polyline,
    placedAt = parseInstant(placed_at),
    items = items.map {
        OrderItem(
            id = it.id,
            orderLocalId = client_local_id,
            menuItemId = it.menu_item_id,
            nameSnapshot = it.name_snapshot,
            unitPriceMinor = it.unit_price_minor,
            quantity = it.quantity,
        )
    },
    events = events.map {
        OrderEvent(
            id = it.id,
            orderLocalId = client_local_id,
            status = parseOrderStatus(it.status),
            occurredAt = parseInstant(it.occurred_at),
            note = it.note,
        )
    },
)
