package com.voicecontrol.core.data.auth

import kotlinx.serialization.Serializable

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class RegisterRequestDto(val email: String, val password: String, val name: String?)

@Serializable
data class UserDto(val id: String, val email: String, val name: String? = null)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: UserDto,
)
