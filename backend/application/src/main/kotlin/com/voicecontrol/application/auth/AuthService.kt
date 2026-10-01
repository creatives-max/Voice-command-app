package com.voicecontrol.application.auth

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.user.AccessTokenIssuer
import com.voicecontrol.domain.user.PasswordHasher
import com.voicecontrol.domain.user.SessionInfo
import com.voicecontrol.domain.user.SessionStore
import com.voicecontrol.domain.user.TokenPair
import com.voicecontrol.domain.user.User
import com.voicecontrol.domain.user.UserRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AuthResult(val user: User, val tokens: TokenPair)

/** A signed-in session as shown to its owner; [current] is the one making the request. */
data class SessionView(val info: SessionInfo, val current: Boolean)

/**
 * Registration, login, refresh-token rotation, password changes and signed-in sessions. Access tokens are
 * short-lived JWTs carrying the session id. After [MAX_FAILED_LOGINS] wrong passwords for an email within
 * [LOCKOUT_SECONDS], that account can't sign in until the time is up (on top of the per-IP limit).
 */
class AuthService(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val issuer: AccessTokenIssuer,
    private val sessions: SessionStore,
    private val failures: Cache? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun register(email: String, password: String, name: String?, client: String? = null): AuthResult {
        val normalized = validateEmail(email)
        validatePassword(password)
        if (users.findByEmail(normalized) != null) throw DomainException.Conflict("An account with this email already exists")
        val user = users.create(
            User(UUID.randomUUID(), normalized, name?.trim()?.takeIf { it.isNotEmpty() }?.take(120), hasher.hash(password), clock.instant()),
        )
        return AuthResult(user, issue(user, newSessionId(), client))
    }

    suspend fun login(email: String, password: String, client: String? = null): AuthResult {
        val normalized = email.trim().lowercase()
        val failKey = "vc:login-failures:$normalized"
        val failed = failures?.get(failKey)?.toIntOrNull() ?: 0
        if (failed >= MAX_FAILED_LOGINS) throw DomainException.RateLimited(LOCKED_MESSAGE)
        val user = users.findByEmail(normalized)
        // Same error for unknown email and wrong password: no account enumeration.
        if (user == null || !hasher.verify(password, user.passwordHash)) {
            failures?.put(failKey, (failed + 1).toString(), LOCKOUT_SECONDS)
            if (failed + 1 >= MAX_FAILED_LOGINS) throw DomainException.RateLimited(LOCKED_MESSAGE)
            throw DomainException.Unauthorized("Invalid email or password")
        }
        if (failed > 0) failures?.delete(failKey)
        return AuthResult(user, issue(user, newSessionId(), client))
    }

    /** Rotates the refresh token; the session keeps its id and start time. */
    suspend fun refresh(refreshToken: String, client: String? = null): AuthResult {
        val session = sessions.consume(refreshToken) ?: throw DomainException.Unauthorized("Session expired, please sign in again")
        val user = users.findById(session.userId) ?: throw DomainException.Unauthorized("Account no longer exists")
        return AuthResult(user, issue(user, session.sessionId, session.client ?: client, session.createdAt))
    }

    /**
     * Changes the password after checking the current one and signs out every other session
     * ([currentSessionId] stays signed in).
     */
    suspend fun changePassword(userId: UUID, currentPassword: String, newPassword: String, currentSessionId: String?) {
        val user = users.findById(userId) ?: throw DomainException.NotFound("User not found")
        if (!hasher.verify(currentPassword, user.passwordHash)) throw DomainException.Forbidden("Current password is not correct")
        validatePassword(newPassword)
        if (newPassword == currentPassword) throw DomainException.Validation("Choose a password different from the current one")
        users.updatePassword(userId, hasher.hash(newPassword), clock.instant())
        for (s in sessions.list(userId)) {
            if (s.id != currentSessionId) sessions.revokeSession(userId, s.id)
        }
    }

    suspend fun sessions(userId: UUID, currentSessionId: String?): List<SessionView> =
        sessions.list(userId).map { SessionView(it, it.id == currentSessionId) }

    suspend fun signOutSession(userId: UUID, sessionId: String) {
        if (!sessions.revokeSession(userId, sessionId)) throw DomainException.NotFound("That session has already ended")
    }

    suspend fun logout(refreshToken: String?, userId: UUID, everywhere: Boolean) {
        if (everywhere) sessions.revokeAll(userId) else refreshToken?.let { sessions.revoke(it) }
    }

    suspend fun me(userId: UUID): User = users.findById(userId) ?: throw DomainException.NotFound("User not found")

    private suspend fun issue(user: User, sessionId: String, client: String?, createdAt: Instant? = null): TokenPair {
        val (access, ttl) = issuer.issue(user, sessionId)
        return TokenPair(access, sessions.create(user.id, sessionId, client, createdAt), ttl)
    }

    private fun newSessionId() = UUID.randomUUID().toString()

    private fun validateEmail(email: String): String {
        val e = email.trim().lowercase()
        if (e.length > 254 || !EMAIL.matches(e)) throw DomainException.Validation("Please enter a valid email address")
        return e
    }

    private fun validatePassword(password: String) {
        if (password.length < 8) throw DomainException.Validation("Password must be at least 8 characters")
        if (password.length > 128) throw DomainException.Validation("Password is too long")
        if (password.none(Char::isLetter) || password.none(Char::isDigit)) {
            throw DomainException.Validation("Password must contain letters and numbers")
        }
    }

    companion object {
        const val MAX_FAILED_LOGINS = 10
        const val LOCKOUT_SECONDS = 15 * 60L
        const val LOCKED_MESSAGE = "Too many wrong passwords for this account. Try again in 15 minutes."
        private val EMAIL = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$")
    }
}
