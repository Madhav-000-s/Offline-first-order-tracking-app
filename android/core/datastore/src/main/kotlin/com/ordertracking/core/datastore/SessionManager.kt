package com.ordertracking.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "session")

data class SessionState(val isLoggedIn: Boolean, val userId: String?)

// Kotlin top-level `private` is file-scoped for *access*, but declaration
// names still must be unique per package -- hence the SessionKeys/
// AppPreferencesKeys naming rather than both files reusing "Keys".
private object SessionKeys {
    val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
    val USER_ID = stringPreferencesKey("user_id")
}

/**
 * Lightweight, non-secret session state only -- *not* where the JWT access
 * or refresh token lives. Those need encryption at rest and are handled by
 * :core:network's EncryptedSharedPreferences/DataStore+Tink storage
 * (DESIGN.md §13); this class just answers "are we logged in, as whom" for
 * screens that need to gate on it without touching the token store.
 */
class SessionManager(private val context: Context) {

    val session: Flow<SessionState> = context.sessionDataStore.data.map { prefs ->
        SessionState(
            isLoggedIn = prefs[SessionKeys.IS_LOGGED_IN] ?: false,
            userId = prefs[SessionKeys.USER_ID],
        )
    }

    suspend fun setLoggedIn(userId: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[SessionKeys.IS_LOGGED_IN] = true
            prefs[SessionKeys.USER_ID] = userId
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
