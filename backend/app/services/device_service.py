import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models.device import Device


async def upsert(db: AsyncSession, user_id: uuid.UUID, fcm_token: str, platform: str) -> Device:
    existing = await db.scalar(
        select(Device).where(Device.user_id == user_id, Device.fcm_token == fcm_token)
    )
    if existing is not None:
        existing.registered_at = datetime.now(timezone.utc)
        await db.commit()
        return existing

    device = Device(user_id=user_id, fcm_token=fcm_token, platform=platform, registered_at=datetime.now(timezone.utc))
    db.add(device)
    await db.commit()
    await db.refresh(device)
    return device


async def list_tokens(db: AsyncSession, user_id: uuid.UUID) -> list[str]:
    rows = await db.scalars(select(Device.fcm_token).where(Device.user_id == user_id))
    return list(rows.all())
