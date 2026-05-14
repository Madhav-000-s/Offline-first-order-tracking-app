import hashlib
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.db.models.idempotency_key import IdempotencyKey
from app.db.session import AsyncSessionLocal

settings = get_settings()

ClaimKind = Literal["claimed", "replay", "in_flight", "conflict"]


@dataclass
class ClaimResult:
    kind: ClaimKind
    stored_status: int | None = None
    stored_body: dict[str, Any] | None = None


def hash_request(payload: dict[str, Any]) -> str:
    import json

    canonical = json.dumps(payload, sort_keys=True, default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


async def claim(user_id: uuid.UUID, key: str, request_hash: str) -> ClaimResult:
    """Atomically claim an idempotency key via INSERT ... ON CONFLICT DO NOTHING.

    This runs in its own short transaction, committed immediately, on purpose:
    if the claim lived in the same transaction as the (potentially slow) order
    creation below, concurrent callers would just block on the row lock and
    only ever observe the final `completed` state once we commit, and the
    documented `in_flight` -> 409 path (DESIGN.md §14.2 step 4) would never
    actually be reachable. Committing the claim eagerly makes `in_flight`
    genuinely observable to a request that arrives while we're still working.
    """
    now = datetime.now(timezone.utc)
    async with AsyncSessionLocal() as claim_db:
        stmt = (
            insert(IdempotencyKey)
            .values(
                user_id=user_id,
                key=key,
                request_hash=request_hash,
                status="in_flight",
                created_at=now,
                expires_at=now + timedelta(hours=settings.idempotency_key_ttl_hours),
            )
            .on_conflict_do_nothing(index_elements=["user_id", "key"])
            .returning(IdempotencyKey.key)
        )
        result = await claim_db.execute(stmt)
        claimed = result.first() is not None
        await claim_db.commit()

        if claimed:
            return ClaimResult(kind="claimed")

        existing = await claim_db.scalar(
            select(IdempotencyKey).where(IdempotencyKey.user_id == user_id, IdempotencyKey.key == key)
        )
        assert existing is not None  # the conflict proves a row exists

        if existing.request_hash != request_hash:
            return ClaimResult(kind="conflict")
        if existing.status == "in_flight":
            return ClaimResult(kind="in_flight")
        return ClaimResult(
            kind="replay", stored_status=existing.response_status, stored_body=existing.response_body
        )


async def complete(db: AsyncSession, user_id: uuid.UUID, key: str, status_code: int, body: dict[str, Any]) -> None:
    row = await db.get(IdempotencyKey, {"user_id": user_id, "key": key})
    assert row is not None
    row.status = "completed"
    row.response_status = status_code
    row.response_body = body
