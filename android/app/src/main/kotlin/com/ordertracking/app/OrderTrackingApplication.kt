package com.ordertracking.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager

class OrderTrackingApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // On-demand WorkManager init (paired with removing the default
        // startup initializer in AndroidManifest.xml) so it picks up the
        // custom WorkerFactory wiring OutboxDrainWorker and DeltaSyncWorker.
        val workerFactory = DelegatingWorkerFactory().apply {
            addFactory(container.outboxDrainWorkerFactory)
            addFactory(container.deltaSyncWorkerFactory)
        }
        WorkManager.initialize(this, Configuration.Builder().setWorkerFactory(workerFactory).build())

        container.syncManager.schedulePeriodicSync()
    }
}
