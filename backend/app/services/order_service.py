import asyncio
import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.enums import OrderStatus, auto_next_status, can_transition, is_terminal
from app.core.errors import AppError, InFlightConflict, IdempotencyConflict
from app.db.models.menu_item import MenuItem
from app.db.models.order import Order
from app.db.models.order_event import OrderEvent
from app.db.models.order_item import OrderItem
from app.db.models.restaurant import Restaurant
from app.db.session import AsyncSessionLocal
from app.realtime import pubsub
from app.realtime.fixture_routes import encode_polyline, pick_fixture_route
from app.schemas.order import PlaceOrderRequest
from app.services import device_service, idempotency_service, push_service
from app.services.mappers import order_out


class OrderNotFound(AppError):
    status_code = 404


class InvalidTransition(AppError):
    status_code = 409


def _order_query():
    return select(Order).options(selectinload(Order.items), selectinload(Order.events))


async def get_order(db: AsyncSession, user_id: uuid.UUID, order_id: uuid.UUID) -> Order:
    order = await db.scalar(_order_query().where(Order.id == order_id, Order.user_id == user_id))
    if order is None:
        raise OrderNotFound(f"order {order_id} not found")
    return order


async def create_order(
    db: AsyncSession, user_id: uuid.UUID, idempotency_key: str, body: PlaceOrderRequest
) -> tuple[int, dict]:
    request_hash = idempotency_service.hash_request(body.model_dump(mode="json"))
    claim = await idempotency_service.claim(user_id, idempotency_key, request_hash)

    if claim.kind == "conflict":
        raise IdempotencyConflict("Idempotency-Key reused with a different request body")
    if claim.kind == "in_flight":
        raise InFlightConflict("request with this Idempotency-Key is already being processed")
    if claim.kind == "replay":
        assert claim.stored_status is not None and claim.stored_body is not None
        return claim.stored_status, claim.stored_body

    restaurant = await db.get(Restaurant, body.restaurant_id)
    if restaurant is None or restaurant.deleted_at is not None:
        raise OrderNotFound(f"restaurant {body.restaurant_id} not found")

    menu_item_ids = [i.menu_item_id for i in body.items]
    menu_items = {
        m.id: m
        for m in (
            await db.scalars(
                select(MenuItem).where(MenuItem.id.in_(menu_item_ids), MenuItem.restaurant_id == body.restaurant_id)
            )
        ).all()
    }
    missing = set(menu_item_ids) - menu_items.keys()
    if missing:
        raise OrderNotFound(f"menu items not found on this restaurant: {sorted(str(m) for m in missing)}")

    now = datetime.now(timezone.utc)
    total_minor = sum(menu_items[i.menu_item_id].price_minor * i.quantity for i in body.items)

    order = Order(
        user_id=user_id,
        restaurant_id=body.restaurant_id,
        client_local_id=idempotency_key,
        status=OrderStatus.PLACED,
        total_minor=total_minor,
        currency=next(iter(menu_items.values())).currency,
        placed_at=now,
        delivery_note=body.delivery_note,
        tip_minor=body.tip_minor,
        # Precomputed now, from a fixture route set, so there's no external
        # routing API dependency later when the courier simulator walks it
        # (DESIGN.md §14.4).
        route_polyline=encode_polyline(pick_fixture_route()),
    )
    order.items = [
        OrderItem(
            menu_item_id=item.menu_item_id,
            name_snapshot=menu_items[item.menu_item_id].name,
            unit_price_minor=menu_items[item.menu_item_id].price_minor,
            quantity=item.quantity,
        )
        for item in body.items
    ]
    order.events = [OrderEvent(status=OrderStatus.PLACED, occurred_at=now, note=None)]

    db.add(order)
    await db.flush()
    await db.refresh(order, attribute_names=["items", "events", "version", "updated_at"])

    response_body = order_out(order).model_dump(mode="json")
    await idempotency_service.complete(db, user_id, idempotency_key, 201, response_body)
    await db.commit()

    from app.workers import state_advancer  # local import breaks the state_advancer <-> order_service cycle

    state_advancer.start(order.id, order.status)

    return 201, response_body


async def cancel_order(db: AsyncSession, user_id: uuid.UUID, order_id: uuid.UUID, idempotency_key: str) -> dict:
    request_hash = idempotency_service.hash_request({"op": "cancel", "order_id": str(order_id)})
    claim = await idempotency_service.claim(user_id, idempotency_key, request_hash)

    if claim.kind == "conflict":
        raise IdempotencyConflict("Idempotency-Key reused with a different request body")
    if claim.kind == "in_flight":
        raise InFlightConflict("request with this Idempotency-Key is already being processed")
    if claim.kind == "replay":
        assert claim.stored_body is not None
        return claim.stored_body

    order = await get_order(db, user_id, order_id)
    if not is_terminal(order.status) and can_transition(order.status, OrderStatus.CANCELLED):
        order.status = OrderStatus.CANCELLED
        order.events.append(OrderEvent(status=OrderStatus.CANCELLED, occurred_at=datetime.now(timezone.utc), note=None))
        await db.flush()
        # `version`/`updated_at` are computed by Postgres via onupdate (see
        # SyncedMixin), so the ORM's in-memory copy is stale until refreshed.
        # Doing that refresh here -- inside an awaited call -- matters: the
        # mapper below touches these attributes synchronously, and letting an
        # expired attribute lazy-load there blows up async SQLAlchemy with
        # "greenlet_spawn has not been called".
        await db.refresh(order, attribute_names=["version", "updated_at", "events"])
    elif not is_terminal(order.status):
        raise InvalidTransition(f"cannot cancel an order in status {order.status}")
    # Already CANCELLED/DELIVERED/REJECTED: cancelling again is a no-op success,
    # not an error, so a retried cancel is idempotent even without this key.

    response_body = order_out(order).model_dump(mode="json")
    await idempotency_service.complete(db, user_id, idempotency_key, 200, response_body)
    await db.commit()
    return response_body


async def transition_and_notify(
    order_id: uuid.UUID, target_status: OrderStatus, note: str | None = None
) -> Order:
    """System-level transition (courier simulator, state-advancer timers, the
    dev advance endpoint) -- not scoped to a request's user, so it opens its
    own session rather than taking one via dependency injection.

    Publishes to Redis and sends the push notification only *after* commit,
    never inside the transaction: publishing first would let a client fetch
    via REST and observe stale data before the write is even durable, the
    classic dual-write ordering bug (DESIGN.md §14.3).
    """
    async with AsyncSessionLocal() as db:
        # FOR UPDATE: the courier simulator's own final PICKED_UP -> DELIVERED
        # and, say, a manually-clicked dev/advance can genuinely land at the
        # same moment. Without a row lock here both would read the same
        # pre-transition status, both pass the FSM check, and both write --
        # two duplicate terminal OrderEvent rows for the same order. Locking
        # makes the second caller re-check against the *post*-transition
        # status once it's unblocked, so it correctly sees "already there"
        # instead of duplicating the transition.
        order = await db.scalar(_order_query().where(Order.id == order_id).with_for_update())
        if order is None:
            raise OrderNotFound(f"order {order_id} not found")
        if not can_transition(order.status, target_status):
            raise InvalidTransition(f"cannot transition {order.status} -> {target_status}")

        order.status = target_status
        order.events.append(
            OrderEvent(status=target_status, occurred_at=datetime.now(timezone.utc), note=note)
        )
        await db.commit()
        await db.refresh(order, attribute_names=["version", "updated_at", "events"])

        order_id_str = str(order.id)
        user_id = order.user_id
        status_value = order.status.value
        version = order.version
        route_polyline = order.route_polyline

    await pubsub.publish(
        order_id_str,
        {"v": 1, "type": "order_status", "order_id": order_id_str, "version": version, "data": {"status": status_value}},
    )
    async with AsyncSessionLocal() as db:
        tokens = await device_service.list_tokens(db, user_id)
    await push_service.send_order_status_push(tokens, order_id_str, status_value)

    if target_status == OrderStatus.PICKED_UP and route_polyline:
        from app.realtime import courier_simulator
        from app.realtime.fixture_routes import decode_polyline

        asyncio.create_task(courier_simulator.simulate(order_id, decode_polyline(route_polyline)))

    return order


async def advance_to_next(order_id: uuid.UUID) -> Order | None:
    """Convenience for the dev endpoint / state-advancer: transition to
    whatever the single "happy path" successor of the order's *current*
    status is, looked up fresh (not trusted from a stale caller-held object)."""
    async with AsyncSessionLocal() as db:
        current = await db.scalar(select(Order.status).where(Order.id == order_id))
    if current is None:
        raise OrderNotFound(f"order {order_id} not found")
    target = auto_next_status(current)
    if target is None:
        raise InvalidTransition(f"order in status {current} has no automatic next status")
    return await transition_and_notify(order_id, target)
