package com.ordertracking.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.ordertracking.core.designsystem.OrderTrackingTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as OrderTrackingApplication).container

    /**
     * The foreground sync trigger (DESIGN.md §8). `onStart` rather than
     * `onCreate` so it fires on every return to the foreground, not just on
     * a cold launch -- coming back after an hour in the background is
     * exactly when the local cache is most stale and the outbox most likely
     * to be holding writes from a process that was killed mid-drain.
     */
    override fun onStart() {
        super.onStart()
        container.syncManager.onAppForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                OrderTrackingTheme {
                    OrderTrackingNavHost()
                }
            }
        }
    }
}
