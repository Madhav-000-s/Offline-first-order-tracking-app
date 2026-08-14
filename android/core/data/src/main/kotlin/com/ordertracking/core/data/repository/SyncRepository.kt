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
import com.ordertracking.core.database.dao.OrderDao
import com.ordertracking.core.database.dao.RestaurantDao
import com.ordertracking.core.database.dao.SyncCursorDao
import com.ordertracking.core.database.entity.SyncCursorEntity
import com.ordertracking.core.network.ApiService

private const val CURSOR_RESOURCE = "all"

/**
 * The client half of the delta protocol (DESIGN.md §8). The cursor is
 * server-opaque and persisted in Room -- never a client timestamp -- and
 * every order in the page goes through [OrderWriter], the same single
 * writer the WebSocket path funnels through, so a page replayed after a
 * crash mid-page is idempotent by the version guard alone.
 */
class SyncRepository(
    private val api: ApiService,
    private val orderWriter: OrderWriter,
    private val orderDao: OrderDao,
    private val restaurantDao: RestaurantDao,
    private val menuItemDao: MenuItemDao,
    private val syncCursorDao: SyncCursorDao,
    private val clock: AppClock,
) {

    /** Returns Success(hasMore) so a caller can keep paging while true. */
    suspend fun syncOnce(limit: Int = 200): Outcome<Boolean> = try {
        val cursorRow = syncCursorDao.find(CURSOR_RESOURCE)
        val response = api.sync(cursor = cursorRow?.cursor, limit = limit)

        // Tombstones (DESIGN.md §8): a delta page carries deletions as rows
        // flagged `deleted`, not as absences -- an absent row is
        // indistinguishable from "unchanged since your cursor", so a
        // deletion has to be an explicit fact on the wire. Partition first,
        // then apply, so a row that was upserted by an earlier page and
        // deleted in a later one converges to gone.
        val (deletedRestaurants, liveRestaurants) = response.changes.restaurants.partition { it.deleted }
        if (liveRestaurants.isNotEmpty()) {
            restaurantDao.upsertAll(liveRestaurants.map { it.toEntity() })
        }
        if (deletedRestaurants.isNotEmpty()) {
            restaurantDao.deleteByIds(deletedRestaurants.map { it.id })
        }

        val (deletedMenuItems, liveMenuItems) = response.changes.menu_items.partition { it.deleted }
        if (liveMenuItems.isNotEmpty()) {
            menuItemDao.upsertAll(liveMenuItems.map { it.toEntity() })
        }
        if (deletedMenuItems.isNotEmpty()) {
            menuItemDao.deleteByIds(deletedMenuItems.map { it.id })
        }

        response.changes.orders.forEach { dto ->
            if (dto.deleted) {
                // Deliberately not the same thing as CANCELLED: a cancelled
                // order stays in the user's history, a tombstoned one was
                // purged server-side and has no business surviving here.
                orderDao.deleteByServerId(dto.id)
            } else {
                orderWriter.apply(dto.toRemoteSnapshot(), channel = "REST")
            }
        }

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
