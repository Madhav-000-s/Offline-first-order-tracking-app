import base64
import json
import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import tuple_
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db.models.menu_item import MenuItem
from app.db.models.order import Order
from app.db.models.restaurant import Restaurant
from app.schemas.sync import SyncChanges, SyncResponse
from app.services.mappers import menu_item_out, order_out, restaurant_out

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_RESOURCES = ("orders", "restaurants", "menu_items")


def _encode_cursor(positions: dict[str, tuple[str, str]]) -> str:
    return base64.urlsafe_b64encode(json.dumps(positions).encode("utf-8")).decode("ascii")


def _decode_cursor(cursor: str | None) -> dict[str, tuple[str, str]]:
    if not cursor:
        return {r: (_EPOCH.isoformat(), "00000000-0000-0000-0000-000000000000") for r in _RESOURCES}
    raw = json.loads(base64.urlsafe_b64decode(cursor.encode("ascii")).decode("utf-8"))
    return {r: tuple(raw[r]) for r in _RESOURCES}


async def get_delta(db: AsyncSession, user_id: uuid.UUID, cursor: str | None, limit: int) -> SyncResponse:
    """Keyset-paginated delta sync across every synced resource in one opaque
    cursor. Never a client-supplied timestamp: the cursor is server-opaque so
    correctness never depends on the device clock (DESIGN.md §8)."""
    positions = _decode_cursor(cursor)
    has_more = False
    next_positions = dict(positions)

    orders_pos = positions["orders"]
    order_rows = (await db.scalars(_select_orders(user_id, orders_pos, limit + 1))).all()
    if len(order_rows) > limit:
        has_more = True
        order_rows = order_rows[:limit]
    if order_rows:
        last = order_rows[-1]
        next_positions["orders"] = (last.updated_at.isoformat(), str(last.id))

    restaurants_pos = positions["restaurants"]
    restaurant_rows = (await db.scalars(_select_restaurants(restaurants_pos, limit + 1))).all()
    if len(restaurant_rows) > limit:
        has_more = True
        restaurant_rows = restaurant_rows[:limit]
    if restaurant_rows:
        last = restaurant_rows[-1]
        next_positions["restaurants"] = (last.updated_at.isoformat(), str(last.id))

    menu_pos = positions["menu_items"]
    menu_rows = (await db.scalars(_select_menu_items(menu_pos, limit + 1))).all()
    if len(menu_rows) > limit:
        has_more = True
        menu_rows = menu_rows[:limit]
    if menu_rows:
        last = menu_rows[-1]
        next_positions["menu_items"] = (last.updated_at.isoformat(), str(last.id))

    changes = SyncChanges(
        orders=[order_out(o) for o in order_rows],
        restaurants=[restaurant_out(r) for r in restaurant_rows],
        menu_items=[menu_item_out(m) for m in menu_rows],
    )
    return SyncResponse(
        changes=changes,
        next_cursor=_encode_cursor(next_positions),
        has_more=has_more,
        server_time=datetime.now(timezone.utc),
    )


def _keyset_filter(model: Any, pos: tuple[str, str]):
    cursor_updated_at = datetime.fromisoformat(pos[0])
    cursor_id = uuid.UUID(pos[1])
    return tuple_(model.updated_at, model.id) > tuple_(cursor_updated_at, cursor_id)


def _select_orders(user_id: uuid.UUID, pos: tuple[str, str], limit: int):
    from sqlalchemy import select

    return (
        select(Order)
        .options(selectinload(Order.items), selectinload(Order.events))
        .where(Order.user_id == user_id, _keyset_filter(Order, pos))
        .order_by(Order.updated_at, Order.id)
        .limit(limit)
    )


def _select_restaurants(pos: tuple[str, str], limit: int):
    from sqlalchemy import select

    return select(Restaurant).where(_keyset_filter(Restaurant, pos)).order_by(Restaurant.updated_at, Restaurant.id).limit(limit)


def _select_menu_items(pos: tuple[str, str], limit: int):
    from sqlalchemy import select

    return select(MenuItem).where(_keyset_filter(MenuItem, pos)).order_by(MenuItem.updated_at, MenuItem.id).limit(limit)
