package com.ordertracking.app

import androidx.compose.runtime.compositionLocalOf

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("AppContainer not provided -- wrap the composition in CompositionLocalProvider(LocalAppContainer provides ...)")
}
