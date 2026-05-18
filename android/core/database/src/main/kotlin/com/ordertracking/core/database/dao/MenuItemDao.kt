package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ordertracking.core.database.entity.MenuItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuItemDao {

    @Query("SELECT * FROM menu_items WHERE restaurantId = :restaurantId ORDER BY name")
    fun observeForRestaurant(restaurantId: String): Flow<List<MenuItemEntity>>

    @Query("SELECT * FROM menu_items WHERE id = :id")
    suspend fun findById(id: String): MenuItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MenuItemEntity>)
}
