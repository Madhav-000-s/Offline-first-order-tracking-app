package com.ordertracking.sync

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

sealed interface OutboxOutcome {
    data object Success : OutboxOutcome
    /** 5xx, timeout, IO, or 409 (in-flight duplicate) -- retry with backoff. */
    data object Retryable : OutboxOutcome
    /** Any other 4xx. Silently discarding a user's order because the server said 422 is unacceptable. */
    data class Permanent(val message: String) : OutboxOutcome
}

fun classifyFailure(t: Throwable): OutboxOutcome = when (t) {
    is HttpException -> when (val code = t.code()) {
        in 500..599, 409 -> OutboxOutcome.Retryable
        else -> OutboxOutcome.Permanent("HTTP $code: ${t.message() ?: "request rejected"}")
    }
    is SocketTimeoutException, is IOException -> OutboxOutcome.Retryable
    else -> OutboxOutcome.Permanent(t.message ?: t::class.simpleName ?: "unknown error")
}
