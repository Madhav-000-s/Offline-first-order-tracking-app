package com.ordertracking.core.network.auth

import com.ordertracking.core.network.dto.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException

/**
 * Handles 401 -> refresh -> retry. Guarded by a Mutex so twelve concurrent
 * 401s produce one refresh call, not twelve (DESIGN.md §13). `refreshCall`
 * is a plain suspend function backed by a *separate* OkHttpClient with no
 * auth interceptor/authenticator of its own -- routing the refresh request
 * through this same authenticated client would recurse.
 *
 * This rotates an existing session; it can never create one. The initial
 * pair comes from `AuthRepository` in `:app`, and [onAuthLost] is how this
 * class tells that layer the session is gone for good.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val onAuthLost: suspend () -> Unit = {},
    private val refreshCall: suspend (refreshToken: String) -> TokenPair,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 3) return null // give up rather than retry forever

        val presentedTokens = tokenStore.read() ?: return null

        val refreshedAccessToken = runBlocking {
            mutex.withLock {
                // Another request may have already refreshed while we waited for the lock.
                val latest = tokenStore.read()
                if (latest != null && latest.accessToken != presentedTokens.accessToken) {
                    return@withLock latest.accessToken
                }
                try {
                    val newTokens = refreshCall(presentedTokens.refreshToken)
                    tokenStore.save(newTokens)
                    newTokens.accessToken
                } catch (e: Exception) {
                    // A 4xx from /auth/refresh means the refresh token itself
                    // is dead -- expired, or revoked because reuse was
                    // detected on its family -- and no amount of retrying
                    // fixes that. Drop the session so the app routes back to
                    // login instead of silently 401ing forever.
                    //
                    // A *network* failure must not do the same. This app is
                    // offline-first; signing the user out every time they
                    // open it in airplane mode would be a bug wearing a
                    // security costume. Keep the tokens and let the next
                    // authenticated request try again.
                    if (e is HttpException && e.code() in 400..499) {
                        tokenStore.clear()
                        onAuthLost()
                    }
                    null
                }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedAccessToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
