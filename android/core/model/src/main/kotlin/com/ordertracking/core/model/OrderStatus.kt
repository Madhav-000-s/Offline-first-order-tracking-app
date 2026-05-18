package com.ordertracking.core.model

/**
 * Server-authoritative and monotonically forward for the non-terminal path
 * (DESIGN.md §5). Declaration order *is* the ordinal the merge engine
 * compares (`status.ordinal`) -- PLACED=0 .. DELIVERED=5 -- so this order is
 * load-bearing, not cosmetic. CANCELLED/REJECTED sit outside that ladder
 * entirely; [isTerminal] is checked before ordinal comparison ever happens.
 */
enum class OrderStatus {
    PLACED,
    ACCEPTED,
    PREPARING,
    READY,
    PICKED_UP,
    DELIVERED,
    CANCELLED,
    REJECTED;

    val isTerminal: Boolean
        get() = this == DELIVERED || this == CANCELLED || this == REJECTED
}
