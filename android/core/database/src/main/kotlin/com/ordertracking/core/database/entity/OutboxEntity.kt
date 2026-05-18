package com.ordertracking.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * One row per pending mutation. Inserted in the *same* Room transaction as
 * the entity it describes, so an order can never exist without its outbox
 * entry or vice versa (DESIGN.md §7).
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String, // "order", "order_cancel", "fcm_token"
    val entityLocalId: String,
    val operation: String, // CREATE | CANCEL | UPDATE
    val payloadJson: String,
    val createdAt: Instant,
    val attemptCount: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null,
)
