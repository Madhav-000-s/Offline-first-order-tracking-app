package com.ordertracking.core.data.repository

import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.data.mapper.toDomain
import com.ordertracking.core.data.mapper.toEntity
import com.ordertracking.core.database.dao.MenuItemDao
import com.ordertracking.core.database.dao.RestaurantDao
import com.ordertracking.core.database.entity.RemoteKeyEntity
import com.ordertracking.core.model.MenuItem
import com.ordertracking.core.model.Restaurant
import com.ordertracking.core.network.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads: always Room Flow. This basic first-page refresh is a stepping
 * stone -- full Paging 3 + RemoteMediator lands in a later phase, but the
 * transactional "clear + insert in one go, no empty-list flash" contract
 * (DESIGN.md §11) is already the right shape for it to slot into.
 */
class RestaurantRepository(
    private val api: ApiService,
    private val restaurantDao: RestaurantDao,
    private val menuItemDao: MenuItemDao,
) {
    fun observeRestaurants(): Flow<List<Restaurant>> =
        restaurantDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeMenu(restaurantId: String): Flow<List<MenuItem>> =
        menuItemDao.observeForRestaurant(restaurantId).map { rows -> rows.map { it.toDomain() } }

    suspend fun refreshFirstPage(pageSize: Int = 20): Outcome<Unit> = try {
        val page = api.restaurants(cursor = null, limit = pageSize)
        val entities = page.items.map { it.toEntity() }
        val keys = page.items.map { RemoteKeyEntity(restaurantId = it.id, prevKey = null, nextKey = page.next_cursor) }
        restaurantDao.refreshPage(entities, keys, isFirstPage = true)
        Unit.asSuccess()
    } catch (e: Exception) {
        AppError.Network(e.message ?: "failed to refresh restaurants", e).asFailure()
    }

    suspend fun refreshMenu(restaurantId: String): Outcome<Unit> = try {
        menuItemDao.upsertAll(api.menu(restaurantId).map { it.toEntity() })
        Unit.asSuccess()
    } catch (e: Exception) {
        AppError.Network(e.message ?: "failed to refresh menu", e).asFailure()
    }
}
