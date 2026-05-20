package com.ordertracking.core.data.repository

import com.ordertracking.core.common.AppClock
import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.data.mapper.toEntity
import com.ordertracking.core.data.mapper.toRemoteSnapshot
import com.ordertracking.core.data.merge.OrderWriter
import com.ordertracking.core.database.dao.MenuItemDao
import com.ordertracking.core.database.dao.RestaurantDao
import com.ordertracking.core.database.dao.SyncCursorDao
import com.ordertracking.core.database.entity.SyncCursorEntity
import com.ordertracking.core.network.ApiService

private const val CURSOR_RESOURCE = "all"

/**
 * The client half of the delta protocol (DESIGN.md §8). The cursor is
 * server-opaque and persisted in Room -- never a client timestamp -- and
 * every order in the page goes through [OrderWriter], the same single
 * writer WS and FCM funnel through, so a page replayed after a crash
 * mid-page is idempotent by the version guard alone.
 */
class SyncRepository(
    private val api: ApiService,
    private val orderWriter: OrderWriter,
    private val restaurantDao: RestaurantDao,
    private val menuItemDao: MenuItemDao,
    private val syncCursorDao: SyncCursorDao,
    private val clock: AppClock,
) {

    /** Returns Success(hasMore) so a caller can keep paging while true. */
    suspend fun syncOnce(limit: Int = 200): Outcome<Boolean> = try {
        val cursorRow = syncCursorDao.find(CURSOR_RESOURCE)
        val response = api.sync(cursor = cursorRow?.cursor, limit = limit)

        if (response.changes.restaurants.isNotEmpty()) {
            restaurantDao.upsertAll(response.changes.restaurants.map { it.toEntity() })
        }
        if (response.changes.menu_items.isNotEmpty()) {
            menuItemDao.upsertAll(response.changes.menu_items.map { it.toEntity() })
        }
        response.changes.orders.forEach { orderWriter.apply(it.toRemoteSnapshot(), channel = "REST") }

        // Cursor persisted only after every row in the page has been merged:
        // crash mid-page -> cursor unchanged -> page replays -> merges are
        // idempotent by the version guard, so replaying is always safe.
        syncCursorDao.upsert(
            SyncCursorEntity(resource = CURSOR_RESOURCE, cursor = response.next_cursor, lastSyncAt = clock.now()),
        )
        response.has_more.asSuccess()
    } catch (e: Exception) {
        AppError.Network(e.message ?: "sync failed", e).asFailure()
    }
}
