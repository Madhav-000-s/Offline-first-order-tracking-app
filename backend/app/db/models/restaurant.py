from sqlalchemy import Index, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.db.models.mixins import SyncedMixin, UUIDPKMixin


class Restaurant(UUIDPKMixin, SyncedMixin, Base):
    __tablename__ = "restaurants"
    __table_args__ = (Index("ix_restaurants_updated_at_id", "updated_at", "id"),)

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    cuisine: Mapped[str] = mapped_column(String(120), nullable=False, default="")
    rating: Mapped[float] = mapped_column(Numeric(2, 1), nullable=False, default=0)
    image_url: Mapped[str] = mapped_column(String(1024), nullable=False, default="")
    lat: Mapped[float] = mapped_column(Numeric(9, 6), nullable=False, default=0)
    lng: Mapped[float] = mapped_column(Numeric(9, 6), nullable=False, default=0)
