package com.ordertracking.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequestDto(val email: String, val password: String, val display_name: String = "")

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class RefreshRequestDto(val refresh_token: String)

@Serializable
data class UserDto(val id: String, val email: String, val display_name: String)

@Serializable
data class TokenPairResponseDto(
    val access_token: String,
    val refresh_token: String,
    val token_type: String,
    val user: UserDto,
)

@Serializable
data class RefreshResponseDto(val access_token: String, val refresh_token: String, val token_type: String)
