import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.db.models.mixins import UUIDPKMixin


class Device(UUIDPKMixin, Base):
    __tablename__ = "devices"
    __table_args__ = (UniqueConstraint("user_id", "fcm_token", name="uq_devices_user_token"),)

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False)
    fcm_token: Mapped[str] = mapped_column(String(512), nullable=False)
    platform: Mapped[str] = mapped_column(String(20), nullable=False, default="android")
    registered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
