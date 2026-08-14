package com.ordertracking.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val PERIODIC_SYNC_WORK_NAME = "delta_sync_periodic"

/**
 * The one place every sync trigger funnels through (DESIGN.md §8's trigger
 * table): app foreground, FCM data message, the 15-minute periodic tick,
 * pull-to-refresh, and a WS sequence gap all enqueue [DeltaSyncWorker.WORK_NAME]
 * with `KEEP` so a burst collapses into one run -- except pull-to-refresh,
 * which uses `REPLACE` because the user explicitly asked for a fresh one.
 */
class SyncManager(private val workManager: WorkManager) {

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueOutboxDrain() {
        val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        workManager.enqueueUniqueWork(OutboxDrainWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueDeltaSync() {
        val request = OneTimeWorkRequestBuilder<DeltaSyncWorker>()
            .setConstraints(connectedConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(DeltaSyncWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueuePullToRefresh() {
        val request = OneTimeWorkRequestBuilder<DeltaSyncWorker>()
            .setConstraints(connectedConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(DeltaSyncWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * The foreground trigger. Both halves matter and they are not the same
     * job: the delta sync pulls down whatever changed while the app was
     * away, the outbox drain pushes up whatever was written offline. A
     * process that was killed mid-drain has pending rows and no scheduled
     * work to move them, so coming back to the foreground has to re-arm it.
     *
     * Both are unique work, so returning to the foreground repeatedly
     * collapses into one run rather than queueing a burst.
     */
    fun onAppForeground() {
        enqueueDeltaSync()
        enqueueOutboxDrain()
    }

    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<DeltaSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
