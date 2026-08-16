package com.ordertracking.app.auth

import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.datastore.SessionManager
import com.ordertracking.core.network.ApiService
import com.ordertracking.core.network.auth.TokenPair
import com.ordertracking.core.network.auth.TokenStore
import com.ordertracking.core.network.dto.LoginRequestDto
import com.ordertracking.core.network.dto.RegisterRequestDto
import com.ordertracking.core.network.dto.TokenPairResponseDto
import java.io.IOException
import retrofit2.HttpException

/**
 * The one place that establishes a session. Everything else in the app
 * assumes a bearer token already exists: [com.ordertracking.core.network.auth.AuthInterceptor]
 * attaches whatever [TokenStore] holds, and
 * [com.ordertracking.core.network.auth.TokenAuthenticator] only ever *rotates*
 * an existing pair -- neither can create one from nothing.
 *
 * Lives in `:app` rather than a `:feature:*` module for the same reason the
 * WebSocket lives in `:feature:tracking`: this is one of the two places that
 * legitimately needs `:core:network` directly, and `:app` is already the
 * module that owns [TokenStore] and [SessionManager] construction.
 */
class AuthRepository(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val sessionManager: SessionManager,
) {

    suspend fun login(email: String, password: String): Outcome<Unit> =
        establishSession { api.login(LoginRequestDto(email = email.trim(), password = password)) }

    suspend fun register(email: String, password: String, displayName: String): Outcome<Unit> =
        establishSession {
            api.register(
                RegisterRequestDto(
                    email = email.trim(),
                    password = password,
                    display_name = displayName.trim(),
                ),
            )
        }

    /** Drops both halves of the session. Safe to call when already logged out. */
    suspend fun logout() {
        tokenStore.clear()
        sessionManager.clear()
    }

    private suspend fun establishSession(call: suspend () -> TokenPairResponseDto): Outcome<Unit> = try {
        val response = call()
        // Tokens before session, and never the other way round: the nav
        // graph gates on SessionManager, so flipping that first would let an
        // authenticated screen fire a request in the window before the
        // bearer token is actually readable.
        tokenStore.save(TokenPair(response.access_token, response.refresh_token))
        sessionManager.setLoggedIn(response.user.id)
        Unit.asSuccess()
    } catch (e: Exception) {
        e.toAppError().asFailure()
    }
}

/**
 * Auth is the one call path with no outbox behind it -- there is nothing to
 * queue and retry, because without a token there is no authenticated request
 * to make. So failures are mapped straight to a message the login form can
 * show, rather than to [com.ordertracking.sync.OutboxOutcome]'s
 * retryable/permanent split.
 */
private fun Throwable.toAppError(): AppError = when (this) {
    is HttpException -> when (code()) {
        401 -> AppError.Unauthorized("Incorrect email or password")
        409 -> AppError.Conflict("That email is already registered — try signing in instead")
        422 -> AppError.Validation("Enter a valid email and a password of at least 8 characters")
        in 500..599 -> AppError.Network("The server is having trouble. Try again in a moment.")
        else -> AppError.Unknown("Sign-in failed (HTTP ${code()})", this)
    }
    is IOException -> AppError.Network("Couldn't reach the server. Check your connection.", this)
    else -> AppError.Unknown(message ?: "Sign-in failed", this)
}
