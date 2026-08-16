package com.ordertracking.app.auth

import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.datastore.SessionManager
import com.ordertracking.core.network.ApiService
import com.ordertracking.core.network.auth.TokenPair
import com.ordertracking.core.network.auth.TokenStore
import com.ordertracking.core.network.dto.LoginRequestDto
import com.ordertracking.core.network.dto.RegisterRequestDto
import com.ordertracking.core.network.dto.TokenPairResponseDto
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val db: AppDatabase,
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

    /**
     * Drops the session *and* the local cache. Safe to call when already
     * logged out.
     *
     * Room is a per-account cache here, not a shared one: it holds the
     * signed-in user's orders and their outbox. Keeping it across a logout
     * would show the next person to sign in on this device the previous
     * user's order history, and would let the drain push their queued
     * writes under a different account's token.
     *
     * The cost is real and deliberate -- signing out with a write still
     * queued discards it. That beats delivering it as somebody else.
     * Credentials go first so nothing can start a fresh authenticated
     * request while the wipe is in progress.
     */
    suspend fun logout() {
        tokenStore.clear()
        sessionManager.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
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
