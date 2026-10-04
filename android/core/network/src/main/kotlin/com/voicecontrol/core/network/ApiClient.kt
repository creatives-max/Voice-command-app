package com.voicecontrol.core.network

import com.voicecontrol.core.network.dto.ApiErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

val NetworkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * How the phone introduces itself, so people recognise it in their signed-in sessions
 * ("VoiceControl app 1.4.0 on Android"). Only the app version and phone model, nothing personal.
 */
fun clientUserAgent(appVersion: String?, model: String?, androidRelease: String?): String {
    val clean = { v: String? -> v?.replace(Regex("[^\\w .\\-]"), "")?.trim()?.take(40)?.takeIf { it.isNotEmpty() } }
    val details = listOfNotNull(clean(model), clean(androidRelease)?.let { "Android $it" }).joinToString("; ")
    return "VoiceControl-Android/${clean(appVersion) ?: "0"}" + if (details.isEmpty()) "" else " ($details)"
}

/** Builds the shared Ktor client; the engine is injected so tests can use MockEngine. */
fun createHttpClient(engine: io.ktor.client.engine.HttpClientEngine, debug: Boolean, userAgent: String? = null): HttpClient = HttpClient(engine) {
    expectSuccess = false
    if (userAgent != null) install(UserAgent) { agent = userAgent }
    install(ContentNegotiation) { json(NetworkJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        // The server gives the AI up to 24 s for a step; the phone waits a little longer than that.
        requestTimeoutMillis = 35_000
        socketTimeoutMillis = 35_000
    }
    if (debug) install(Logging) { level = LogLevel.INFO }
}

/**
 * Thin authenticated JSON client: resolves the base URL per call (it is user-configurable),
 * attaches the bearer token, retries once after refreshing on 401, and maps errors to [ApiException].
 */
class ApiClient(
    val http: HttpClient,
    private val session: BackendSession,
) {
    suspend fun send(
        method: HttpMethod,
        path: String,
        authenticated: Boolean = true,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        suspend fun attempt(): HttpResponse {
            val base = session.baseUrl().trimEnd('/')
            val token = if (authenticated) session.accessToken() else null
            return try {
                http.request {
                    this.method = method
                    url("$base$path")
                    contentType(ContentType.Application.Json)
                    if (token != null) bearerAuth(token)
                    block()
                }
            } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
                throw ApiException(0, "timeout", "The server took too long to respond")
            } catch (e: IOException) {
                throw ApiException(0, "network_error", e.message ?: "Network error")
            }
        }
        var response = attempt()
        if (authenticated && response.status.value == 401 && session.refreshTokens()) response = attempt()
        if (!response.status.isSuccess()) {
            val err = runCatching { NetworkJson.decodeFromString(ApiErrorDto.serializer(), response.bodyAsText()) }.getOrNull()
            throw ApiException(response.status.value, err?.error ?: "http_${response.status.value}", err?.message ?: response.status.description)
        }
        return response
    }

    suspend inline fun <reified T> get(path: String, authenticated: Boolean = true, noinline block: HttpRequestBuilder.() -> Unit = {}): T =
        send(HttpMethod.Get, path, authenticated, block).body()

    suspend inline fun <reified B : Any, reified T> post(path: String, body: B, authenticated: Boolean = true): T =
        send(HttpMethod.Post, path, authenticated) { setBody(body) }.body()

    suspend inline fun <reified T> postEmpty(path: String, authenticated: Boolean = true): T =
        send(HttpMethod.Post, path, authenticated).body()

    suspend inline fun <reified B : Any, reified T> put(path: String, body: B): T =
        send(HttpMethod.Put, path) { setBody(body) }.body()

    suspend inline fun <reified B : Any, reified T> patch(path: String, body: B): T =
        send(HttpMethod.Patch, path) { setBody(body) }.body()

    suspend fun delete(path: String) {
        send(HttpMethod.Delete, path)
    }
}
