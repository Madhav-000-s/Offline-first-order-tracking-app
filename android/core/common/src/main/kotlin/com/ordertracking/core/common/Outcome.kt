package com.ordertracking.core.common

/**
 * Repository reads return `Flow<T>` off Room; repository writes return this
 * rather than throwing for *expected* failures (DESIGN.md §15) -- a rejected
 * idempotency replay or a validation error is a value, not an exception.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

fun <T> T.asSuccess(): Outcome<T> = Outcome.Success(this)

fun AppError.asFailure(): Outcome<Nothing> = Outcome.Failure(this)
