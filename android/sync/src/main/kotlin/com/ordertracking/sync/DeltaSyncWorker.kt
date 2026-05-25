package com.ordertracking.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.data.repository.SyncRepository

private const val MAX_PAGES_PER_RUN = 25

/**
 * All five triggers (foreground, FCM, periodic, pull-to-refresh, WS gap)
 * enqueue this same unique work name so a burst collapses into one run
 * (DESIGN.md §8).
 */
class DeltaSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var pages = 0
        while (pages < MAX_PAGES_PER_RUN) {
            when (val outcome = syncRepository.syncOnce()) {
                is Outcome.Success -> {
                    pages++
                    if (!outcome.value) return Result.success()
                }
                is Outcome.Failure -> return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "delta_sync"
    }
}

class DeltaSyncWorkerFactory(private val syncRepository: SyncRepository) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == DeltaSyncWorker::class.java.name) {
        DeltaSyncWorker(appContext, workerParameters, syncRepository)
    } else {
        null
    }
}
