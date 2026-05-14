import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.errors import EmailAlreadyExists, InvalidCredentials, InvalidRefreshToken, RefreshTokenReused
from app.core.security import (
    create_access_token,
    hash_password,
    hash_refresh_token,
    new_refresh_token_value,
    verify_password,
)
from app.db.models.refresh_token import RefreshToken
from app.db.models.user import User

settings = get_settings()


async def _issue_refresh_token(db: AsyncSession, user_id: uuid.UUID, family_id: uuid.UUID | None = None) -> tuple[str, RefreshToken]:
    raw = new_refresh_token_value()
    now = datetime.now(timezone.utc)
    row = RefreshToken(
        user_id=user_id,
        family_id=family_id or uuid.uuid4(),
        token_hash=hash_refresh_token(raw),
        created_at=now,
        expires_at=now + timedelta(days=settings.refresh_token_expire_days),
    )
    db.add(row)
    await db.flush()
    return raw, row


async def register(db: AsyncSession, email: str, password: str, display_name: str) -> tuple[User, str, str]:
    existing = await db.scalar(select(User).where(User.email == email))
    if existing is not None:
        raise EmailAlreadyExists(f"{email} is already registered")

    user = User(email=email, password_hash=hash_password(password), display_name=display_name)
    db.add(user)
    await db.flush()

    access = create_access_token(str(user.id))
    refresh, _ = await _issue_refresh_token(db, user.id)
    await db.commit()
    return user, access, refresh


async def login(db: AsyncSession, email: str, password: str) -> tuple[User, str, str]:
    user = await db.scalar(select(User).where(User.email == email))
    if user is None or not verify_password(password, user.password_hash):
        raise InvalidCredentials("invalid email or password")

    access = create_access_token(str(user.id))
    refresh, _ = await _issue_refresh_token(db, user.id)
    await db.commit()
    return user, access, refresh


async def refresh_tokens(db: AsyncSession, presented_token: str) -> tuple[str, str]:
    """Rotate a refresh token. Guarded so reuse of an already-rotated token
    revokes the whole family rather than silently failing (DESIGN.md §13)."""
    token_hash = hash_refresh_token(presented_token)
    row = await db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_hash))

    if row is None:
        raise InvalidRefreshToken("unknown refresh token")

    now = datetime.now(timezone.utc)

    if row.revoked_at is not None:
        # This exact token was already rotated away and is being replayed.
        # Assume compromise: revoke every token in the family.
        await db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == row.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        await db.commit()
        raise RefreshTokenReused("refresh token reuse detected; family revoked")

    if row.expires_at < now:
        raise InvalidRefreshToken("refresh token expired")

    row.revoked_at = now
    new_raw, _ = await _issue_refresh_token(db, row.user_id, family_id=row.family_id)
    access = create_access_token(str(row.user_id))
    await db.commit()
    return access, new_raw


async def logout(db: AsyncSession, presented_token: str) -> None:
    token_hash = hash_refresh_token(presented_token)
    now = datetime.now(timezone.utc)
    await db.execute(
        update(RefreshToken)
        .where(RefreshToken.token_hash == token_hash, RefreshToken.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    await db.commit()
