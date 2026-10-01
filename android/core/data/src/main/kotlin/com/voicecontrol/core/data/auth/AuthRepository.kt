package com.voicecontrol.core.data.auth

import com.voicecontrol.core.data.sync.SyncScheduler
import com.voicecontrol.core.network.ApiException
import com.voicecontrol.core.network.AuthApi
import com.voicecontrol.core.network.dto.AuthResponseDto
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Sign-in state for the app. Tokens are kept encrypted in [TokenStore]. */
@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokens: TokenStore,
    private val sync: SyncScheduler,
) {
    val user: Flow<SignedInUser?> = tokens.user

    suspend fun login(email: String, password: String): Result<SignedInUser> =
        runCatching { store(api.login(email.trim(), password)) }.mapApiError()

    suspend fun register(email: String, password: String, name: String?): Result<SignedInUser> =
        runCatching { store(api.register(email.trim(), password, name?.trim()?.ifEmpty { null })) }.mapApiError()

    suspend fun logout() {
        val refresh = tokens.tokens()?.refreshToken
        runCatching { api.logout(refresh) }
        tokens.clear()
    }

    private suspend fun store(auth: AuthResponseDto): SignedInUser {
        val user = SignedInUser(auth.user.id, auth.user.email, auth.user.name)
        tokens.save(AuthTokens(auth.accessToken, auth.refreshToken, System.currentTimeMillis() + auth.expiresInSeconds * 1000), user)
        // First sync after sign-in: pull profile, upload locally recorded flows and history.
        sync.syncNow(pullProfile = true)
        return user
    }

    private fun <T> Result<T>.mapApiError(): Result<T> = recoverCatching { e ->
        throw when (e) {
            is ApiException -> IllegalStateException(
                when {
                    e.isNetwork -> "Can't reach the VoiceControl server. Check the server address in Settings."
                    else -> e.message ?: "Request failed"
                },
            )
            else -> e
        }
    }
}
