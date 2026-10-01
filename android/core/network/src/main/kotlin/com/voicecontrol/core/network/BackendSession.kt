package com.voicecontrol.core.network

/**
 * Where to reach the backend and how to authenticate. Implemented by `core:data`
 * (settings + encrypted token store) so the network layer stays storage-agnostic.
 */
interface BackendSession {
    suspend fun baseUrl(): String
    suspend fun accessToken(): String?
    /** Exchanges the refresh token for new tokens. Returns false when the user must sign in again. */
    suspend fun refreshTokens(): Boolean
}

/** Error returned by the backend (`{"error": "...", "message": "..."}`) or a transport failure. */
class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message) {
    val isUnauthorized: Boolean get() = status == 401
    val isNetwork: Boolean get() = status == 0
}
