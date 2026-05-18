package com.ordertracking.core.common

/**
 * Domain errors as a sealed hierarchy, mapped from HTTP at the network
 * boundary. Nothing above :core:data should ever see an HttpException or a
 * raw exception type from Retrofit/OkHttp (DESIGN.md §15).
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Unauthorized(message: String) : AppError(message)
    class Validation(message: String) : AppError(message)
    class Conflict(message: String) : AppError(message)
    class Unknown(message: String, cause: Throwable? = null) : AppError(message, cause)
}
