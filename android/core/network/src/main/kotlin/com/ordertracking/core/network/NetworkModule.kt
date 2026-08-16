package com.ordertracking.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ordertracking.core.network.auth.AuthInterceptor
import com.ordertracking.core.network.auth.TokenAuthenticator
import com.ordertracking.core.network.auth.TokenPair
import com.ordertracking.core.network.auth.TokenStore
import com.ordertracking.core.network.dto.RefreshRequestDto
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Manual DI wiring for this module (Hilt bindings land in :app once that
 * module exists). Retrofit over Ktor client specifically for OkHttp's
 * Authenticator/interceptor ecosystem, and because the same OkHttp instance
 * serves the WebSocket -- one connection pool, one TLS config, one auth
 * story (DESIGN.md §13).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class NetworkModule(
    baseUrl: String,
    tokenStore: TokenStore,
    debug: Boolean,
    /** Invoked when the refresh token is rejected outright -- see [TokenAuthenticator]. */
    onAuthLost: suspend () -> Unit = {},
) {

    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** No auth interceptor/authenticator -- used only to call /v1/auth/refresh itself. */
    private val plainOkHttp: OkHttpClient = baseClientBuilder(debug).build()

    private val plainRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(plainOkHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val plainApi: ApiService = plainRetrofit.create(ApiService::class.java)

    private val authenticator = TokenAuthenticator(tokenStore, onAuthLost) { refreshToken ->
        val result = plainApi.refresh(RefreshRequestDto(refreshToken))
        TokenPair(result.access_token, result.refresh_token)
    }

    val okHttpClient: OkHttpClient = baseClientBuilder(debug)
        .addInterceptor(AuthInterceptor(tokenStore))
        .authenticator(authenticator)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)

    private fun baseClientBuilder(debug: Boolean): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (debug) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
                redactHeader("Authorization")
                redactHeader("Idempotency-Key")
            }
            builder.addInterceptor(logging)
        }
        return builder
    }
}
