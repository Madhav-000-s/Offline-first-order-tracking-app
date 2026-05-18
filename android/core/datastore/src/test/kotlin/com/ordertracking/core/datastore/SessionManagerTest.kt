package com.ordertracking.core.datastore

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val sessionManager = SessionManager(context)

    // JUnit doesn't guarantee method execution order, and the underlying
    // Preferences DataStore file isn't guaranteed to reset between test
    // methods sharing the same Robolectric Application instance -- so tests
    // must not depend on ambient "fresh install" state, only on state they
    // set up themselves.
    @Before
    fun resetSession() = runTest {
        sessionManager.clear()
    }

    @Test
    fun `starts logged out`() = runTest {
        val state = sessionManager.session.first()
        assertFalse(state.isLoggedIn)
        assertNull(state.userId)
    }

    @Test
    fun `setLoggedIn persists the user id`() = runTest {
        sessionManager.setLoggedIn("user-123")
        val state = sessionManager.session.first()
        assertEquals(true, state.isLoggedIn)
        assertEquals("user-123", state.userId)
    }

    @Test
    fun `clear resets to logged out`() = runTest {
        sessionManager.setLoggedIn("user-123")
        sessionManager.clear()
        val state = sessionManager.session.first()
        assertFalse(state.isLoggedIn)
        assertNull(state.userId)
    }
}
