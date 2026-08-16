package com.ordertracking.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ordertracking.core.database.entity.RemoteKeyEntity
import com.ordertracking.core.database.entity.RestaurantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RestaurantDao {

    @Query("SELECT * FROM restaurants ORDER BY name")
    fun observeAll(): Flow<List<RestaurantEntity>>

    /** The UI pages off SQLite; `RemoteMediator` only ever writes into it (DESIGN.md §11). */
    @Query("SELECT * FROM restaurants ORDER BY name")
    fun pagingSource(): PagingSource<Int, RestaurantEntity>

    @Query("SELECT * FROM restaurants WHERE id = :id")
    fun observeOne(id: String): Flow<RestaurantEntity?>

    /**
     * `@Upsert`, never `@Insert(onConflict = REPLACE)`.
     *
     * REPLACE compiles to SQLite's `INSERT OR REPLACE`, which resolves a
     * conflict by *deleting* the existing row and inserting a new one -- and
     * `menu_items` holds a foreign key to this table with ON DELETE CASCADE.
     * So re-upserting a restaurant the cache already had silently deleted
     * every one of its menu items, on every single feed page load. `@Upsert`
     * issues a real UPDATE on conflict, so nothing is ever deleted and the
     * cascade never fires.
     */
    @Upsert
    suspend fun upsertAll(restaurants: List<RestaurantEntity>)

    @Query("DELETE FROM restaurants")
    suspend fun clearAll()

    /** Tombstone application: the server said this row is gone. */
    @Query("DELETE FROM restaurants WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM remote_keys")
    suspend fun clearRemoteKeys()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRemoteKeys(keys: List<RemoteKeyEntity>)

    @Query("SELECT * FROM remote_keys WHERE restaurantId = :restaurantId")
    suspend fun remoteKey(restaurantId: String): RemoteKeyEntity?

    /**
     * REFRESH rebuilds the paging keys and upserts the page in one
     * transaction, so there's never an empty-list flash (DESIGN.md §11).
     *
     * It deliberately does *not* clear `restaurants`. `menu_items` holds a
     * foreign key to this table with ON DELETE CASCADE, so `clearAll()` here
     * silently emptied the entire menu cache on every refresh -- and a
     * refresh fires whenever the Pager restarts, including on a plain
     * navigate back to the feed. The visible symptom was a blank menu screen
     * for every restaurant; the real damage was that no menu row written by
     * *any* path, delta sync included, could survive.
     *
     * Removing a restaurant that no longer exists server-side is the delta
     * sync tombstone path's job ([deleteByIds]), which is exactly the
     * distinction the protocol draws: an absent row means "unchanged", only
     * an explicit `deleted` flag means gone.
     */
    @Transaction
    suspend fun refreshPage(restaurants: List<RestaurantEntity>, keys: List<RemoteKeyEntity>, isFirstPage: Boolean) {
        if (isFirstPage) {
            clearRemoteKeys()
        }
        upsertAll(restaurants)
        upsertRemoteKeys(keys)
    }
}
