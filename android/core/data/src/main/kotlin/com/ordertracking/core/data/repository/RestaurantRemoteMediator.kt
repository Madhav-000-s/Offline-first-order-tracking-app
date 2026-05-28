package com.ordertracking.core.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.ordertracking.core.data.mapper.toEntity
import com.ordertracking.core.database.dao.RestaurantDao
import com.ordertracking.core.database.entity.RemoteKeyEntity
import com.ordertracking.core.database.entity.RestaurantEntity
import com.ordertracking.core.network.ApiService

/**
 * Network writes pages into SQLite; the UI pages off SQLite (DESIGN.md
 * §11) -- a network-only PagingSource would show a spinner in airplane
 * mode, this shows the last-known feed instead. The cost is the
 * `remote_keys` bookkeeping, the tradeoff worth naming out loud.
 */
@OptIn(ExperimentalPagingApi::class)
class RestaurantRemoteMediator(
    private val api: ApiService,
    private val restaurantDao: RestaurantDao,
) : RemoteMediator<Int, RestaurantEntity>() {

    override suspend fun load(loadType: LoadType, state: PagingState<Int, RestaurantEntity>): MediatorResult {
        return try {
            val cursor = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> {
                    val lastItem = state.lastItemOrNull()
                        ?: return MediatorResult.Success(endOfPaginationReached = true)
                    restaurantDao.remoteKey(lastItem.id)?.nextKey
                        ?: return MediatorResult.Success(endOfPaginationReached = true)
                }
            }

            val response = api.restaurants(cursor = cursor, limit = state.config.pageSize)
            val entities = response.items.map { it.toEntity() }
            val keys = response.items.map {
                RemoteKeyEntity(restaurantId = it.id, prevKey = cursor, nextKey = response.next_cursor)
            }

            restaurantDao.refreshPage(entities, keys, isFirstPage = loadType == LoadType.REFRESH)

            MediatorResult.Success(endOfPaginationReached = !response.has_more)
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }
}
