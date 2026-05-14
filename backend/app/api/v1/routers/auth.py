from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.deps import get_current_user
from app.db.models.user import User
from app.db.session import get_db
from app.schemas.auth import LoginRequest, RefreshRequest, RegisterRequest, TokenPairResponse
from app.schemas.user import UserOut
from app.services import auth_service

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=TokenPairResponse, status_code=201)
async def register(body: RegisterRequest, db: AsyncSession = Depends(get_db)) -> TokenPairResponse:
    user, access, refresh = await auth_service.register(db, body.email, body.password, body.display_name)
    return TokenPairResponse(access_token=access, refresh_token=refresh, user=UserOut.model_validate(user))


@router.post("/login", response_model=TokenPairResponse)
async def login(body: LoginRequest, db: AsyncSession = Depends(get_db)) -> TokenPairResponse:
    user, access, refresh = await auth_service.login(db, body.email, body.password)
    return TokenPairResponse(access_token=access, refresh_token=refresh, user=UserOut.model_validate(user))


@router.post("/refresh")
async def refresh(body: RefreshRequest, db: AsyncSession = Depends(get_db)) -> dict:
    access, new_refresh = await auth_service.refresh_tokens(db, body.refresh_token)
    return {"access_token": access, "refresh_token": new_refresh, "token_type": "bearer"}


@router.post("/logout", status_code=204)
async def logout(body: RefreshRequest, db: AsyncSession = Depends(get_db)) -> None:
    await auth_service.logout(db, body.refresh_token)


@router.get("/me", response_model=UserOut)
async def me(current_user: User = Depends(get_current_user)) -> UserOut:
    return UserOut.model_validate(current_user)
