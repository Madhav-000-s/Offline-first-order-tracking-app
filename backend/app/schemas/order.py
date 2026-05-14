import uuid
from datetime import datetime

from pydantic import BaseModel, Field

from app.core.enums import OrderStatus


class OrderItemIn(BaseModel):
    menu_item_id: uuid.UUID
    quantity: int = Field(gt=0, le=50)


class PlaceOrderRequest(BaseModel):
    restaurant_id: uuid.UUID
    items: list[OrderItemIn] = Field(min_length=1)
    delivery_note: str | None = Field(default=None, max_length=500)
    tip_minor: int = Field(default=0, ge=0)


class OrderItemOut(BaseModel):
    id: uuid.UUID
    menu_item_id: uuid.UUID
    name_snapshot: str
    unit_price_minor: int
    quantity: int


class OrderEventOut(BaseModel):
    id: uuid.UUID
    status: OrderStatus
    occurred_at: datetime
    note: str | None


class OrderOut(BaseModel):
    id: uuid.UUID
    client_local_id: str
    restaurant_id: uuid.UUID
    status: OrderStatus
    total_minor: int
    currency: str
    eta: datetime | None
    placed_at: datetime
    delivery_note: str | None
    tip_minor: int
    route_polyline: str | None
    version: int
    updated_at: datetime
    deleted: bool = False
    items: list[OrderItemOut] = []
    events: list[OrderEventOut] = []


class CancelOrderRequest(BaseModel):
    reason: str | None = Field(default=None, max_length=500)
