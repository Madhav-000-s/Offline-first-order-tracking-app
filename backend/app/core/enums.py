from enum import Enum


class OrderStatus(str, Enum):
    PLACED = "PLACED"
    ACCEPTED = "ACCEPTED"
    PREPARING = "PREPARING"
    READY = "READY"
    PICKED_UP = "PICKED_UP"
    DELIVERED = "DELIVERED"
    CANCELLED = "CANCELLED"
    REJECTED = "REJECTED"


# Mirrors the client-side FSM ordinals (DESIGN.md §5) so both sides agree on
# "forward" without either one hardcoding the other's enum order.
ORDER_STATUS_ORDINAL: dict[OrderStatus, int] = {
    OrderStatus.PLACED: 0,
    OrderStatus.ACCEPTED: 1,
    OrderStatus.PREPARING: 2,
    OrderStatus.READY: 3,
    OrderStatus.PICKED_UP: 4,
    OrderStatus.DELIVERED: 5,
}

TERMINAL_STATUSES: frozenset[OrderStatus] = frozenset(
    {OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.REJECTED}
)

VALID_TRANSITIONS: dict[OrderStatus, frozenset[OrderStatus]] = {
    OrderStatus.PLACED: frozenset({OrderStatus.ACCEPTED, OrderStatus.REJECTED, OrderStatus.CANCELLED}),
    OrderStatus.ACCEPTED: frozenset({OrderStatus.PREPARING, OrderStatus.CANCELLED}),
    OrderStatus.PREPARING: frozenset({OrderStatus.READY, OrderStatus.CANCELLED}),
    OrderStatus.READY: frozenset({OrderStatus.PICKED_UP, OrderStatus.CANCELLED}),
    OrderStatus.PICKED_UP: frozenset({OrderStatus.DELIVERED, OrderStatus.CANCELLED}),
    OrderStatus.DELIVERED: frozenset(),
    OrderStatus.CANCELLED: frozenset(),
    OrderStatus.REJECTED: frozenset(),
}


def is_terminal(status: OrderStatus) -> bool:
    return status in TERMINAL_STATUSES


def can_transition(current: OrderStatus, target: OrderStatus) -> bool:
    return target in VALID_TRANSITIONS[current]


# The one "happy path" successor for automatic state advancement (courier
# simulator / dev advance endpoint). Terminal and branch states (CANCELLED,
# REJECTED) have no automatic next step.
_AUTO_NEXT: dict[OrderStatus, OrderStatus] = {
    OrderStatus.PLACED: OrderStatus.ACCEPTED,
    OrderStatus.ACCEPTED: OrderStatus.PREPARING,
    OrderStatus.PREPARING: OrderStatus.READY,
    OrderStatus.READY: OrderStatus.PICKED_UP,
    OrderStatus.PICKED_UP: OrderStatus.DELIVERED,
}


def auto_next_status(current: OrderStatus) -> OrderStatus | None:
    return _AUTO_NEXT.get(current)
