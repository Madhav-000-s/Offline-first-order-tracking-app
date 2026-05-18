package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ordertracking.core.database.entity.CourierLastKnownEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CourierLastKnownDao {

    @Query("SELECT * FROM courier_last_known WHERE orderId = :orderId")
    fun observe(orderId: String): Flow<CourierLastKnownEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CourierLastKnownEntity)
}
