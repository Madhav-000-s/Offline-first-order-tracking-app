package com.ordertracking.core.data.repository

import com.ordertracking.core.data.mapper.toDomain
import com.ordertracking.core.database.dao.OrderDao
import com.ordertracking.core.model.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads only for now -- ALWAYS a Room Flow, never touching the network
 * directly (DESIGN.md §3). The write side (outbox-backed order placement)
 * is :sync's job, next phase; this repository is what that layer and every
 * order-facing screen will read through.
 */
class OrderRepository(private val orderDao: OrderDao) {

    fun observeOrders(): Flow<List<Order>> = orderDao.observeOrders().map { rows -> rows.map { it.toDomain() } }

    fun observeOrder(localId: String): Flow<Order?> = orderDao.observeOrder(localId).map { it?.toDomain() }
}
