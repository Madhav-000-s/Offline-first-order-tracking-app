from datetime import datetime

from pydantic import BaseModel

from app.schemas.order import OrderOut
from app.schemas.restaurant import MenuItemOut, RestaurantOut


class SyncChanges(BaseModel):
    orders: list[OrderOut] = []
    restaurants: list[RestaurantOut] = []
    menu_items: list[MenuItemOut] = []


class SyncResponse(BaseModel):
    changes: SyncChanges
    next_cursor: str
    has_more: bool
    server_time: datetime
