from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.deps import get_current_user
from app.db.models.user import User
from app.db.session import get_db
from app.schemas.device import DeviceIn
from app.services import device_service

router = APIRouter(prefix="/devices", tags=["devices"])


@router.post("", status_code=201)
async def register_device(
    body: DeviceIn, db: AsyncSession = Depends(get_db), user: User = Depends(get_current_user)
) -> dict:
    await device_service.upsert(db, user.id, body.fcm_token, body.platform)
    return {"status": "registered"}
