package com.ordertracking.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ordertracking.core.database.converter.Converters
import com.ordertracking.core.database.dao.CourierLastKnownDao
import com.ordertracking.core.database.dao.MenuItemDao
import com.ordertracking.core.database.dao.OrderDao
import com.ordertracking.core.database.dao.OutboxDao
import com.ordertracking.core.database.dao.RestaurantDao
import com.ordertracking.core.database.dao.SyncCursorDao
import com.ordertracking.core.database.dao.SyncLogDao
import com.ordertracking.core.database.entity.CourierLastKnownEntity
import com.ordertracking.core.database.entity.MenuItemEntity
import com.ordertracking.core.database.entity.OrderEntity
import com.ordertracking.core.database.entity.OrderEventEntity
import com.ordertracking.core.database.entity.OrderItemEntity
import com.ordertracking.core.database.entity.OutboxEntity
import com.ordertracking.core.database.entity.RemoteKeyEntity
import com.ordertracking.core.database.entity.RestaurantEntity
import com.ordertracking.core.database.entity.SyncCursorEntity
import com.ordertracking.core.database.entity.SyncLogEntity

/**
 * `fallbackToDestructiveMigration()` is banned (DESIGN.md §4) -- every
 * schema bump ships an explicit Migration plus a MigrationTest against this
 * exported schema JSON (see build.gradle.kts's `room.schemaLocation`).
 */
@Database(
    entities = [
        OrderEntity::class,
        OrderItemEntity::class,
        OrderEventEntity::class,
        OutboxEntity::class,
        RestaurantEntity::class,
        MenuItemEntity::class,
        RemoteKeyEntity::class,
        CourierLastKnownEntity::class,
        SyncCursorEntity::class,
        SyncLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderDao
    abstract fun outboxDao(): OutboxDao
    abstract fun restaurantDao(): RestaurantDao
    abstract fun menuItemDao(): MenuItemDao
    abstract fun courierLastKnownDao(): CourierLastKnownDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        const val DATABASE_NAME = "order-tracking.db"
    }
}
