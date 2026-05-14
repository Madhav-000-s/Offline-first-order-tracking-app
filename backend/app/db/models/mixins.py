import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column


class UUIDPKMixin:
    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )


class SyncedMixin:
    """Every resource the delta-sync protocol serves carries these three columns.

    `version` is bumped in the same UPDATE statement as the mutation (never
    read-modify-write from Python) so the client's version guard is trustworthy
    under concurrency. `deleted_at` is a tombstone, not a hard delete, so a
    cancelled/purged row can still be replicated to clients as "gone".
    """

    version: Mapped[int] = mapped_column(BigInteger, nullable=False, default=1, server_default="1")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
