package com.ordertracking.core.model

/** Where a locally-created row stands with respect to the server (DESIGN.md §4). */
enum class SyncState {
    PENDING_CREATE,
    SYNCING,
    SYNCED,
    FAILED,
}
