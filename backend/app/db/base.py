from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


# Imported for Alembic autogenerate / metadata registration side effects.
from app.db.models import (  # noqa: E402,F401
    device,
    idempotency_key,
    menu_item,
    order,
    order_event,
    order_item,
    refresh_token,
    restaurant,
    user,
)
