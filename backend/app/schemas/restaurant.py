import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class RestaurantOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    cuisine: str
    rating: float
    image_url: str
    lat: float
    lng: float
    version: int
    updated_at: datetime
    deleted: bool = False


class MenuItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    restaurant_id: uuid.UUID
    name: str
    description: str
    price_minor: int
    currency: str
    image_url: str
    version: int
    updated_at: datetime
    deleted: bool = False


class RestaurantPage(BaseModel):
    items: list[RestaurantOut]
    next_cursor: str | None
    has_more: bool
