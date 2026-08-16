package com.ordertracking.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.common.Outcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, REGISTER }

data class LoginUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    /**
     * Mirrors the backend's own contract (`RegisterRequest.password` is
     * `min_length=8`) so a doomed request never leaves the device, but only
     * for the register path -- rejecting a *sign-in* on password length
     * would lock out any account whose password predates that rule.
     */
    val canSubmit: Boolean
        get() = !isSubmitting && email.isNotBlank() && when (mode) {
            AuthMode.SIGN_IN -> password.isNotBlank()
            AuthMode.REGISTER -> password.length >= MIN_PASSWORD_LENGTH
        }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

sealed interface LoginIntent {
    data class EmailChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data class DisplayNameChanged(val value: String) : LoginIntent
    data object ToggleMode : LoginIntent
    data object Submit : LoginIntent
}

sealed interface LoginEffect {
    data object Authenticated : LoginEffect
}

/**
 * Takes the two auth calls as lambdas rather than an [AuthRepository], the
 * same shape [com.ordertracking.feature.menu.MenuViewModel] takes
 * `placeOrder` -- it keeps the state machine testable on a plain JVM run,
 * with no Context and therefore no EncryptedSharedPreferences to stand up.
 */
class LoginViewModel(
    private val signIn: suspend (email: String, password: String) -> Outcome<Unit>,
    private val createAccount: suspend (email: String, password: String, displayName: String) -> Outcome<Unit>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val effectChannel = Channel<LoginEffect>(Channel.BUFFERED)
    val effects: Flow<LoginEffect> = effectChannel.receiveAsFlow()

    fun onIntent(intent: LoginIntent) {
        when (intent) {
            // Any edit clears the error: leaving "incorrect password" on
            // screen while the user is actively fixing it is just noise.
            is LoginIntent.EmailChanged ->
                _uiState.update { it.copy(email = intent.value, errorMessage = null) }
            is LoginIntent.PasswordChanged ->
                _uiState.update { it.copy(password = intent.value, errorMessage = null) }
            is LoginIntent.DisplayNameChanged ->
                _uiState.update { it.copy(displayName = intent.value, errorMessage = null) }
            is LoginIntent.ToggleMode -> _uiState.update {
                it.copy(
                    mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.REGISTER else AuthMode.SIGN_IN,
                    errorMessage = null,
                )
            }
            is LoginIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        // Flipped synchronously, before the coroutine is even scheduled.
        // Setting it inside `launch` would let two quick taps both clear the
        // guard above before either body runs -- which on the register path
        // means the second request comes back 409 on an account the user
        // just successfully created.
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
            val outcome = when (state.mode) {
                AuthMode.SIGN_IN -> signIn(state.email, state.password)
                AuthMode.REGISTER -> createAccount(state.email, state.password, state.displayName)
            }
            when (outcome) {
                // Deliberately leaves isSubmitting true: the nav host is
                // about to swap this screen out, and re-enabling the button
                // first just invites a second submit.
                is Outcome.Success -> effectChannel.trySend(LoginEffect.Authenticated)
                is Outcome.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = outcome.error.message)
                }
            }
        }
    }
}

/**
 * The one screen in the app that genuinely requires connectivity. Everything
 * downstream reads from Room and works offline, but a session has to be
 * established against the server at least once before the sync workers have
 * anything to authenticate with.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onIntent: (LoginIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Order Tracking", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (state.mode == AuthMode.SIGN_IN) "Sign in to continue" else "Create an account",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            if (state.mode == AuthMode.REGISTER) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = { onIntent(LoginIntent.DisplayNameChanged(it)) },
                    label = { Text("Display name (optional)") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = { onIntent(LoginIntent.EmailChanged(it)) },
                label = { Text("Email") },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.isSubmitting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                supportingText = if (state.mode == AuthMode.REGISTER) {
                    { Text("At least ${LoginUiState.MIN_PASSWORD_LENGTH} characters") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.errorMessage != null) {
                Text(
                    state.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }

            Button(
                onClick = { onIntent(LoginIntent.Submit) },
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (state.mode == AuthMode.SIGN_IN) "Sign in" else "Create account")
            }

            TextButton(
                onClick = { onIntent(LoginIntent.ToggleMode) },
                enabled = !state.isSubmitting,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    if (state.mode == AuthMode.SIGN_IN) {
                        "No account? Create one"
                    } else {
                        "Already have an account? Sign in"
                    },
                )
            }
        }
    }
}
