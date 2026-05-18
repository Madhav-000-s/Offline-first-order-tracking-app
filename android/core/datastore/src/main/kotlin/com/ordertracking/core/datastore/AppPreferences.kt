package com.ordertracking.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

private object AppPreferencesKeys {
    val DEBUG_DRAWER_ENABLED = booleanPreferencesKey("debug_drawer_enabled")
}

/** General app-level settings -- everything that isn't session or sync state. */
class AppPreferences(private val context: Context) {

    val debugDrawerEnabled: Flow<Boolean> = context.appPreferencesDataStore.data.map { prefs ->
        prefs[AppPreferencesKeys.DEBUG_DRAWER_ENABLED] ?: false
    }

    suspend fun setDebugDrawerEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { it[AppPreferencesKeys.DEBUG_DRAWER_ENABLED] = enabled }
    }
}
