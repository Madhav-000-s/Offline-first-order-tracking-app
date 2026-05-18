package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Server cursor per row so LoadType.APPEND knows where to resume across process death. */
@Entity(tableName = "remote_keys")
data class RemoteKeyEntity(
    @PrimaryKey val restaurantId: String,
    val prevKey: String?,
    val nextKey: String?,
)
