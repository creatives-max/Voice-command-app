package com.voicecontrol.api

import com.voicecontrol.api.routes.AuthResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.util.UUID

val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(ContentNegotiation) { json(testJson) } }

suspend fun HttpClient.registerUser(email: String = "user-${UUID.randomUUID()}@example.com", password: String = "secret123"): AuthResponse =
    post("/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(mapOf("email" to email, "password" to password, "name" to "Test User"))
    }.body()
