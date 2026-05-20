package com.ordertracking.core.network.auth

import com.ordertracking.core.network.dto.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Handles 401 -> refresh -> retry. Guarded by a Mutex so twelve concurrent
 * 401s produce one refresh call, not twelve (DESIGN.md §13). `refreshCall`
 * is a plain suspend function backed by a *separate* OkHttpClient with no
 * auth interceptor/authenticator of its own -- routing the refresh request
 * through this same authenticated client would recurse.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
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
