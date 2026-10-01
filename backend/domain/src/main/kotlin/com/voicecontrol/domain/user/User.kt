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
    fun issue(user: User): Pair<String, Long>
}

/** Server-side refresh sessions (Redis): opaque tokens that can be rotated and revoked. */
interface SessionStore {
    suspend fun create(userId: UUID): String
    /** Atomically consumes [refreshToken] and returns its user, or null if unknown/expired. */
    suspend fun consume(refreshToken: String): UUID?
    suspend fun revoke(refreshToken: String)
    suspend fun revokeAll(userId: UUID)
}
