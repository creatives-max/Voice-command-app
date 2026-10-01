package com.voicecontrol.infrastructure.webhook

import com.voicecontrol.application.org.WebhookSender
import com.voicecontrol.domain.org.AttemptResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Posts webhook bodies. Redirects are not followed (a redirect could lead to an internal address the
 * URL policy never checked) and responses are not read beyond the status code.
 */
class HttpWebhookSender(
    private val client: HttpClient = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    },
) : WebhookSender, AutoCloseable {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): AttemptResult {
        val response = client.post(url) {
            headers { headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> append(k, v) } }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val code = response.status.value
        return AttemptResult(code, if (code in 200..299) null else "HTTP $code")
    }

    override fun close() = client.close()
}
