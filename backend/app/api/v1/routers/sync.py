from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.deps import get_current_user
from app.db.models.user import User
from app.db.session import get_db
from app.schemas.sync import SyncResponse
from app.services import sync_service

router = APIRouter(prefix="/sync", tags=["sync"])


@router.get("", response_model=SyncResponse)
async def sync(
    cursor: str | None = Query(default=None),
    limit: int = Query(default=200, ge=1, le=500),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
) -> SyncResponse:
    return await sync_service.get_delta(db, user.id, cursor, limit)
