package com.ordertracking.core.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.data.mapper.toDomain
import com.ordertracking.core.data.mapper.toEntity
import com.ordertracking.core.database.dao.MenuItemDao
import com.ordertracking.core.database.dao.RestaurantDao
import com.ordertracking.core.model.MenuItem
import com.ordertracking.core.model.Restaurant
import com.ordertracking.core.network.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RestaurantRepository(
    private val api: ApiService,
    private val restaurantDao: RestaurantDao,
    private val menuItemDao: MenuItemDao,
) {
    fun observeRestaurants(): Flow<List<Restaurant>> =
        restaurantDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * The feed: Paging 3 + `RemoteMediator`, so the UI always pages off
     * SQLite and only ever sees the network through what the mediator wrote
     * there first. `initialLoadSize` 2x the page size deliberately overfills
     * the first paint (DESIGN.md §11).
     */
    @OptIn(ExperimentalPagingApi::class)
    fun pagedRestaurants(): Flow<PagingData<Restaurant>> = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40),
        remoteMediator = RestaurantRemoteMediator(api, restaurantDao),
        pagingSourceFactory = { restaurantDao.pagingSource() },
    ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    fun observeMenu(restaurantId: String): Flow<List<MenuItem>> =
        menuItemDao.observeForRestaurant(restaurantId).map { rows -> rows.map { it.toDomain() } }

    suspend fun refreshMenu(restaurantId: String): Outcome<Unit> = try {
        menuItemDao.upsertAll(api.menu(restaurantId).map { it.toEntity() })
        Unit.asSuccess()
    } catch (e: Exception) {
        AppError.Network(e.message ?: "failed to refresh menu", e).asFailure()
    }
}
