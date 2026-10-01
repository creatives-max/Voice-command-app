package com.voicecontrol.domain.user

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val email: String,
    val name: String?,
    val passwordHash: String,
    val createdAt: Instant,
    val passwordChangedAt: Instant? = null,
)

/** Personal details reused to pre-fill common form fields (name, email, phone, address…). */
@Serializable
data class Profile(
    val fullName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val addressLine: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val dateOfBirth: String? = null,
)

interface UserRepository {
    suspend fun create(user: User): User
    suspend fun findByEmail(email: String): User?
    suspend fun findById(id: UUID): User?
    suspend fun updatePassword(id: UUID, passwordHash: String, at: Instant): Boolean
}

interface ProfileRepository {
    suspend fun get(userId: UUID): Profile?
    suspend fun upsert(userId: UUID, profile: Profile): Profile
}

interface PasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, hash: String): Boolean
}

/** Issued tokens for a signed-in user. */
data class TokenPair(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

interface AccessTokenIssuer {
    /** A short-lived access token; [sessionId] is carried as the `sid` claim so the session can be recognised. */
    fun issue(user: User, sessionId: String? = null): Pair<String, Long>
}

/** A signed-in browser or phone: it keeps its id while its refresh token rotates. */
data class SessionInfo(val id: String, val client: String?, val createdAt: Instant, val lastUsedAt: Instant)

/** What a consumed refresh token belonged to. */
data class ConsumedSession(val userId: UUID, val sessionId: String, val client: String?, val createdAt: Instant?)

/** Server-side refresh sessions (Redis): opaque tokens that can be rotated and revoked. */
interface SessionStore {
    /**
     * A new refresh token for [sessionId] (a new sign-in, or the next token of a session being refreshed,
     * keeping its [createdAt]). [client] describes the browser or app ("Chrome on Windows").
     */
    suspend fun create(userId: UUID, sessionId: String, client: String?, createdAt: Instant? = null): String
    /** Atomically consumes [refreshToken], or null if unknown/expired. */
    suspend fun consume(refreshToken: String): ConsumedSession?
    /** Signs out the session of [refreshToken]. */
    suspend fun revoke(refreshToken: String)
    suspend fun revokeAll(userId: UUID)
    /** The user's signed-in sessions, most recently used first. */
    suspend fun list(userId: UUID): List<SessionInfo>
    /** Signs out one session of [userId]; false when there is no such session. */
    suspend fun revokeSession(userId: UUID, sessionId: String): Boolean
}
