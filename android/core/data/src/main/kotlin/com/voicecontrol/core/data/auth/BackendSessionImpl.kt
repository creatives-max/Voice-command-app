package com.voicecontrol.core.data.auth

import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.network.BackendSession
import com.voicecontrol.core.network.BuildConfig
import com.voicecontrol.core.network.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies base URL + tokens to the API client and refreshes expired access tokens (single-flight). */
@Singleton
class BackendSessionImpl @Inject constructor(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val http: HttpClient,
) : BackendSession {

    private val refreshLock = Mutex()

    override suspend fun baseUrl(): String =
        settings.appSettings().backendUrl.ifBlank { BuildConfig.DEFAULT_BACKEND_URL }.trimEnd('/')

    override suspend fun accessToken(): String? {
        val current = tokens.tokens() ?: return null
        if (current.accessExpiresAtMillis - EXPIRY_SKEW_MS > System.currentTimeMillis()) return current.accessToken
        return if (refreshTokens()) tokens.tokens()?.accessToken else null
    }

    override suspend fun refreshTokens(): Boolean = refreshLock.withLock {
        val current = tokens.tokens() ?: return false
        // Another caller may have refreshed while we waited for the lock.
        if (current.accessExpiresAtMillis - EXPIRY_SKEW_MS > System.currentTimeMillis() + MIN_FRESH_MS) return true
        val response = runCatching {
            http.post("${baseUrl()}/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(NetworkJson.encodeToString(RefreshRequestDto.serializer(), RefreshRequestDto(current.refreshToken)))
            }
        }.getOrElse { return false }
        if (response.status.value == 401) {
            tokens.clear()
            return false
        }
        if (response.status.value !in 200..299) return false
        val auth = runCatching { NetworkJson.decodeFromString(AuthResponseDto.serializer(), response.bodyAsText()) }.getOrElse { return false }
        tokens.save(
            AuthTokens(auth.accessToken, auth.refreshToken, System.currentTimeMillis() + auth.expiresInSeconds * 1000),
            SignedInUser(auth.user.id, auth.user.email, auth.user.name),
        )
        true
    }

    private companion object {
        const val EXPIRY_SKEW_MS = 30_000L
        const val MIN_FRESH_MS = 60_000L
    }
}
