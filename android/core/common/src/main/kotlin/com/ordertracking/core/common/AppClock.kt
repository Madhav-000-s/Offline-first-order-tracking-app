package com.ordertracking.core.common

import java.time.Instant

/**
 * Wraps the device clock behind an interface so tests can fake "now" --
 * and as a standing reminder that device time is display-only. Merge
 * decisions use `serverUpdatedAt` and `serverVersion` exclusively, never
 * this (DESIGN.md §4).
 */
interface AppClock {
    fun now(): Instant
}

class SystemAppClock : AppClock {
    override fun now(): Instant = Instant.now()
}
