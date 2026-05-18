package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.OrderEventEntity
import com.ordertracking.core.database.entity.OrderItemEntity
import com.ordertracking.core.database.entity.OrderWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {

    @Transaction
    @Query("SELECT * FROM orders WHERE localId = :localId")
    fun observeOrder(localId: String): Flow<OrderWithDetails?>

    @Transaction
    @Query("SELECT * FROM orders ORDER BY placedAtLocal DESC")
    fun observeOrders(): Flow<List<OrderWithDetails>>

    @Query("SELECT * FROM orders WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: String): OrderEntity?

    @Query("SELECT * FROM orders WHERE localId = :localId LIMIT 1")
    suspend fun findByLocalId(localId: String): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<OrderItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<OrderEventEntity>)

    @Update
    suspend fun updateOrder(order: OrderEntity)

    /**
     * Insert order + items + initial event atomically -- transactional
     * atomicity means we can never have an order without its outbox entry,
     * or vice versa, once the caller wraps this together with an outbox
     * insert in one Room transaction (DESIGN.md §7 step 2).
     */
    @Transaction
    suspend fun insertNewOrder(order: OrderEntity, items: List<OrderItemEntity>, event: OrderEventEntity) {
        upsertOrder(order)
        upsertItems(items)
        upsertEvents(listOf(event))
    }
}
