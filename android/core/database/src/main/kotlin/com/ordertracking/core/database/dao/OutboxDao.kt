package com.ordertracking.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ordertracking.core.database.entity.OutboxEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    /** Drained serially, in insertion order -- order-cancel must not overtake order-create. */
    @Query("SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY id ASC")
    suspend fun dueEntries(now: Instant): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    /**
     * Re-arms every deferred entry for one order, and returns how many it
     * touched. Both columns have to be reset, not just one: `nextAttemptAt`
     * is what the drain pushes a year out when it gives up (so the entry
     * would never come due again), and `attemptCount` is what it counts
     * toward MAX_ATTEMPTS (so the retry would give up on its first pass).
     */
    @Query(
        "UPDATE outbox SET nextAttemptAt = :now, attemptCount = 0, lastError = NULL " +
            "WHERE entityLocalId = :entityLocalId",
    )
    suspend fun rearmForRetry(entityLocalId: String, now: Instant): Int

    @Update
    suspend fun update(entry: OutboxEntity)

    @Delete
    suspend fun delete(entry: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: Long)
}
