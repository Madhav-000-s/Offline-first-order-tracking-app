package com.ordertracking.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStatusTest {

    @Test
    fun `ordinals are monotonically forward for the non-terminal path`() {
        assertEquals(0, OrderStatus.PLACED.ordinal)
        assertEquals(1, OrderStatus.ACCEPTED.ordinal)
        assertEquals(2, OrderStatus.PREPARING.ordinal)
        assertEquals(3, OrderStatus.READY.ordinal)
        assertEquals(4, OrderStatus.PICKED_UP.ordinal)
        assertEquals(5, OrderStatus.DELIVERED.ordinal)
    }

    @Test
    fun `terminal statuses are flagged correctly`() {
        assertTrue(OrderStatus.DELIVERED.isTerminal)
        assertTrue(OrderStatus.CANCELLED.isTerminal)
        assertTrue(OrderStatus.REJECTED.isTerminal)
        assertFalse(OrderStatus.PLACED.isTerminal)
        assertFalse(OrderStatus.PICKED_UP.isTerminal)
    }
}
