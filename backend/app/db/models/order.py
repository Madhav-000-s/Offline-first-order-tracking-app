import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Enum as SAEnum, ForeignKey, Index, String, Text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.enums import OrderStatus
from app.db.base import Base
from app.db.models.mixins import SyncedMixin, UUIDPKMixin


class Order(UUIDPKMixin, SyncedMixin, Base):
    __tablename__ = "orders"
    __table_args__ = (
        Index("ix_orders_updated_at_id", "updated_at", "id"),
        Index("ix_orders_user_id", "user_id"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False)
    restaurant_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("restaurants.id"), nullable=False
    )
    # The client's locally-generated UUID (== the Idempotency-Key used to create it).
    # Kept so the row is traceable back to the device that created it; the client
    # itself reconciles via the idempotency response, not via this column.
    client_local_id: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)

    status: Mapped[OrderStatus] = mapped_column(
        SAEnum(OrderStatus, name="order_status", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        default=OrderStatus.PLACED,
    )

    total_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="USD")

    eta: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    placed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    # Client-owned fields: the server only ever echoes back whatever the
    # outbox last pushed, so these are safe to treat as plain LWW (DESIGN.md §6).
    delivery_note: Mapped[str | None] = mapped_column(String(500), nullable=True)
    tip_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)

    # Precomputed at creation from a fixture route set (no external routing
    # API dependency) and walked by the courier simulator once PICKED_UP.
    route_polyline: Mapped[str | None] = mapped_column(Text, nullable=True)

    items: Mapped[list["OrderItem"]] = relationship(back_populates="order", cascade="all, delete-orphan")
    events: Mapped[list["OrderEvent"]] = relationship(back_populates="order", cascade="all, delete-orphan")
