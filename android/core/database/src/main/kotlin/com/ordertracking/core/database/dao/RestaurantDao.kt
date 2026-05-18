package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ordertracking.core.database.entity.RemoteKeyEntity
import com.ordertracking.core.database.entity.RestaurantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RestaurantDao {

    @Query("SELECT * FROM restaurants ORDER BY name")
    fun observeAll(): Flow<List<RestaurantEntity>>

    @Query("SELECT * FROM restaurants WHERE id = :id")
    fun observeOne(id: String): Flow<RestaurantEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(restaurants: List<RestaurantEntity>)

    @Query("DELETE FROM restaurants")
    suspend fun clearAll()

    @Query("DELETE FROM remote_keys")
    suspend fun clearRemoteKeys()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRemoteKeys(keys: List<RemoteKeyEntity>)

    @Query("SELECT * FROM remote_keys WHERE restaurantId = :restaurantId")
    suspend fun remoteKey(restaurantId: String): RemoteKeyEntity?

    /**
     * REFRESH clears restaurants + remote_keys in one transaction with the
     * insert, so there's never an empty-list flash (DESIGN.md §11).
     */
    @Transaction
    suspend fun refreshPage(restaurants: List<RestaurantEntity>, keys: List<RemoteKeyEntity>, isFirstPage: Boolean) {
        if (isFirstPage) {
            clearAll()
            clearRemoteKeys()
        }
        upsertAll(restaurants)
        upsertRemoteKeys(keys)
    }
}
