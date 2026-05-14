import uuid

from fastapi import APIRouter, Depends, Header, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.deps import get_current_user
from app.db.models.user import User
from app.db.session import get_db
from app.schemas.order import CancelOrderRequest, OrderOut, PlaceOrderRequest
from app.services import order_service
from app.services.mappers import order_out

router = APIRouter(prefix="/orders", tags=["orders"])


@router.post("", status_code=201)
async def place_order(
    body: PlaceOrderRequest,
    response: Response,
    idempotency_key: str = Header(alias="Idempotency-Key"),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict:
    status_code, response_body = await order_service.create_order(db, user.id, idempotency_key, body)
    response.status_code = status_code
    return response_body


@router.get("/{order_id}", response_model=OrderOut)
async def get_order(
    order_id: uuid.UUID, db: AsyncSession = Depends(get_db), user: User = Depends(get_current_user)
) -> OrderOut:
    order = await order_service.get_order(db, user.id, order_id)
    return order_out(order)


@router.post("/{order_id}/cancel")
async def cancel_order(
    order_id: uuid.UUID,
    body: CancelOrderRequest,
    idempotency_key: str = Header(alias="Idempotency-Key"),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict:
    return await order_service.cancel_order(db, user.id, order_id, idempotency_key)
