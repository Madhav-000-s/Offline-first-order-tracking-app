import uuid

from fastapi import APIRouter, HTTPException

from app.core.config import get_settings
from app.schemas.order import OrderOut
from app.services import order_service
from app.services.mappers import order_out
from app.workers import state_advancer

settings = get_settings()

router = APIRouter(prefix="/dev", tags=["dev"])


@router.post("/orders/{order_id}/advance", response_model=OrderOut)
async def advance_order(order_id: uuid.UUID) -> OrderOut:
    """The thing you actually click during the interview instead of waiting
    for the timers (DESIGN.md §14.4). Never mounted outside dev builds.

    Cancels this order's pending automatic timer first so a manual click and
    the timer can't both fire the same transition and race one into a
    spurious 409, then re-arms a fresh timer for wherever this leaves it.
    """
    if not settings.dev_endpoints_enabled:
        raise HTTPException(status_code=404, detail="not found")
    state_advancer.cancel(order_id)
    order = await order_service.advance_to_next(order_id)
    state_advancer.start(order_id, order.status)
    return order_out(order)
