package com.ordertracking.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected via a qualifier-annotated provider at the DI-graph level
 * (:app's Hilt module) -- `Dispatchers.IO` is never hardcoded in a class,
 * which is what makes every repository test deterministic under
 * `UnconfinedTestDispatcher` (DESIGN.md §15).
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}
