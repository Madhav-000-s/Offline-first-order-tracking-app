package com.ordertracking.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.ordertracking.core.designsystem.OrderTrackingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as OrderTrackingApplication).container

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                OrderTrackingTheme {
                    OrderTrackingNavHost()
                }
            }
        }
    }
}
