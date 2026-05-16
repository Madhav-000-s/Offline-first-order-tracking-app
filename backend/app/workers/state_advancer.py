import asyncio
import logging
import uuid

from sqlalchemy import select

from app.core.enums import OrderStatus
from app.db.models.order import Order
from app.db.session import AsyncSessionLocal
from app.services import order_service

logger = logging.getLogger(__name__)

# Seconds spent in each non-terminal, non-PICKED_UP status before the next
# automatic transition. Short enough that a demo doesn't require waiting
# eight minutes (DESIGN.md §14.4) -- the dev advance endpoint exists for
# skipping this entirely.
STAGE_DURATIONS_SECONDS: dict[OrderStatus, float] = {
    OrderStatus.PLACED: 8,
    OrderStatus.ACCEPTED: 8,
    OrderStatus.PREPARING: 12,
    OrderStatus.READY: 8,
}

_running_tasks: dict[uuid.UUID, asyncio.Task] = {}


async def _advance_loop(order_id: uuid.UUID, current_status: OrderStatus) -> None:
    status = current_status
    try:
        while status in STAGE_DURATIONS_SECONDS:
            await asyncio.sleep(STAGE_DURATIONS_SECONDS[status])
            try:
                order = await order_service.advance_to_next(order_id)
            except (order_service.OrderNotFound, order_service.InvalidTransition):
                return
            if order is None:
                return
            status = order.status
            # PICKED_UP hands itself off to the courier simulator inside
            # transition_and_notify; DELIVERED/CANCELLED/REJECTED just stop.
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("state advancer crashed for order %s", order_id)
    finally:
        _running_tasks.pop(order_id, None)


def start(order_id: uuid.UUID, current_status: OrderStatus) -> None:
    if current_status not in STAGE_DURATIONS_SECONDS or order_id in _running_tasks:
        return
    _running_tasks[order_id] = asyncio.create_task(_advance_loop(order_id, current_status))


def cancel(order_id: uuid.UUID) -> None:
    """Stop this order's automatic timer. Called before any *external*
    transition (the dev advance endpoint) so a manual click and the timer
    can't race each other into a spurious InvalidTransition 409 -- whoever
    calls this owns re-arming a fresh `start()` for the resulting status."""
    task = _running_tasks.pop(order_id, None)
    if task is not None and not task.done():
        task.cancel()


async def reconcile_in_flight_orders() -> None:
    """Startup reconciler: bare asyncio tasks die with the process, so on
    restart we re-arm a timer for every order that's still mid-flight rather
    than losing it silently. Orders already PICKED_UP get the courier
    simulator restarted from the beginning of its route -- we don't persist
    exact courier progress server-side, which is the one thing a real task
    queue (arq/Celery) would buy over this (DESIGN.md open question #2)."""
    from app.realtime import courier_simulator
    from app.realtime.fixture_routes import decode_polyline

    async with AsyncSessionLocal() as db:
        rows = (
            await db.scalars(
                select(Order).where(Order.status.in_(list(STAGE_DURATIONS_SECONDS) + [OrderStatus.PICKED_UP]))
            )
        ).all()
        in_flight = [(o.id, o.status, o.route_polyline) for o in rows]

    for order_id, status, route_polyline in in_flight:
        if status == OrderStatus.PICKED_UP:
            if route_polyline:
                asyncio.create_task(courier_simulator.simulate(order_id, decode_polyline(route_polyline)))
        else:
            start(order_id, status)

    if in_flight:
        logger.info("reconciled %d in-flight order(s) after restart", len(in_flight))
