package com.ordertracking.app.auth

import app.cash.turbine.test
import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        signIn: suspend (String, String) -> Outcome<Unit> = { _, _ -> error("sign-in not expected") },
        createAccount: suspend (String, String, String) -> Outcome<Unit> = { _, _, _ -> error("register not expected") },
    ) = LoginViewModel(signIn = signIn, createAccount = createAccount)

    @Test
    fun `successful sign-in emits Authenticated`() = runTest(dispatcher) {
        val vm = viewModel(signIn = { _, _ -> Unit.asSuccess() })
        vm.onIntent(LoginIntent.EmailChanged("demo@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("hunter2!"))

        vm.effects.test {
            vm.onIntent(LoginIntent.Submit)
            advanceUntilIdle()
            assertEquals(LoginEffect.Authenticated, awaitItem())
        }
    }

    @Test
    fun `sign-in passes the typed credentials through`() = runTest(dispatcher) {
        var seen: Pair<String, String>? = null
        val vm = viewModel(signIn = { email, password -> seen = email to password; Unit.asSuccess() })

        vm.onIntent(LoginIntent.EmailChanged("demo@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("hunter2!"))
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals("demo@example.com" to "hunter2!", seen)
    }

    @Test
    fun `a rejected sign-in surfaces the message and frees the button`() = runTest(dispatcher) {
        val vm = viewModel(signIn = { _, _ -> AppError.Unauthorized("Incorrect email or password").asFailure() })
        vm.onIntent(LoginIntent.EmailChanged("demo@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("wrong-one"))

        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Incorrect email or password", state.errorMessage)
        // The user has to be able to correct the password and try again --
        // a failed attempt that leaves the form disabled is a dead end.
        assertFalse(state.isSubmitting)
        assertTrue(state.canSubmit)
    }

    @Test
    fun `editing a field clears a stale error`() = runTest(dispatcher) {
        val vm = viewModel(signIn = { _, _ -> AppError.Unauthorized("Incorrect email or password").asFailure() })
        vm.onIntent(LoginIntent.EmailChanged("demo@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("wrong-one"))
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        vm.onIntent(LoginIntent.PasswordChanged("wrong-two"))

        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `register requires the password length the backend enforces`() = runTest(dispatcher) {
        val vm = viewModel(createAccount = { _, _, _ -> Unit.asSuccess() })
        vm.onIntent(LoginIntent.ToggleMode)
        vm.onIntent(LoginIntent.EmailChanged("new@example.com"))

        vm.onIntent(LoginIntent.PasswordChanged("short"))
        assertFalse(vm.uiState.value.canSubmit)

        vm.onIntent(LoginIntent.PasswordChanged("longenough"))
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `sign-in accepts a short password that register would not`() = runTest(dispatcher) {
        val vm = viewModel(signIn = { _, _ -> Unit.asSuccess() })
        vm.onIntent(LoginIntent.EmailChanged("legacy@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("old"))

        // Enforcing the register-time minimum on sign-in would lock out any
        // account whose password predates that rule.
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `submitting twice runs the call once`() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModel(signIn = { _, _ -> calls++; Unit.asSuccess() })
        vm.onIntent(LoginIntent.EmailChanged("demo@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("hunter2!"))

        vm.onIntent(LoginIntent.Submit)
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `toggling to register clears a sign-in error`() = runTest(dispatcher) {
        val vm = viewModel(signIn = { _, _ -> AppError.Unauthorized("Incorrect email or password").asFailure() })
        vm.onIntent(LoginIntent.EmailChanged("demo@example.com"))
        vm.onIntent(LoginIntent.PasswordChanged("hunter2!"))
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        vm.onIntent(LoginIntent.ToggleMode)

        assertEquals(AuthMode.REGISTER, vm.uiState.value.mode)
        assertNull(vm.uiState.value.errorMessage)
    }
}
