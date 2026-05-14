import base64
import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models.menu_item import MenuItem
from app.db.models.restaurant import Restaurant
from app.db.session import get_db
from app.schemas.restaurant import MenuItemOut, RestaurantPage
from app.services.mappers import menu_item_out, restaurant_out

router = APIRouter(prefix="/restaurants", tags=["restaurants"])

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_ZERO_UUID = "00000000-0000-0000-0000-000000000000"


def _encode(pos: tuple[str, str]) -> str:
    return base64.urlsafe_b64encode(json.dumps(list(pos)).encode("utf-8")).decode("ascii")


def _decode(cursor: str | None) -> tuple[datetime, uuid.UUID]:
    if not cursor:
        return _EPOCH, uuid.UUID(_ZERO_UUID)
    raw = json.loads(base64.urlsafe_b64decode(cursor.encode("ascii")).decode("utf-8"))
    return datetime.fromisoformat(raw[0]), uuid.UUID(raw[1])


@router.get("", response_model=RestaurantPage)
async def list_restaurants(
    cursor: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
) -> RestaurantPage:
    cursor_updated_at, cursor_id = _decode(cursor)
    rows = (
        await db.scalars(
            select(Restaurant)
            .where(
                Restaurant.deleted_at.is_(None),
                tuple_(Restaurant.updated_at, Restaurant.id) > tuple_(cursor_updated_at, cursor_id),
            )
            .order_by(Restaurant.updated_at, Restaurant.id)
            .limit(limit + 1)
        )
    ).all()

    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = _encode((rows[-1].updated_at.isoformat(), str(rows[-1].id))) if rows else cursor or _encode((_EPOCH.isoformat(), _ZERO_UUID))

    return RestaurantPage(items=[restaurant_out(r) for r in rows], next_cursor=next_cursor, has_more=has_more)


@router.get("/{restaurant_id}/menu", response_model=list[MenuItemOut])
async def get_menu(restaurant_id: uuid.UUID, db: AsyncSession = Depends(get_db)) -> list[MenuItemOut]:
    rows = (
        await db.scalars(
            select(MenuItem)
            .where(MenuItem.restaurant_id == restaurant_id, MenuItem.deleted_at.is_(None))
            .order_by(MenuItem.name)
        )
    ).all()
    return [menu_item_out(m) for m in rows]
