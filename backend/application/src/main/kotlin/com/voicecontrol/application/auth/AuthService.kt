package com.voicecontrol.application.auth

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.user.AccessTokenIssuer
import com.voicecontrol.domain.user.PasswordHasher
import com.voicecontrol.domain.user.SessionStore
import com.voicecontrol.domain.user.TokenPair
import com.voicecontrol.domain.user.User
import com.voicecontrol.domain.user.UserRepository
import java.time.Clock
import java.util.UUID

data class AuthResult(val user: User, val tokens: TokenPair)

/** Registration, login and refresh-token rotation. Access tokens are short-lived JWTs. */
class AuthService(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val issuer: AccessTokenIssuer,
    private val sessions: SessionStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun register(email: String, password: String, name: String?): AuthResult {
        val normalized = validateEmail(email)
        validatePassword(password)
        if (users.findByEmail(normalized) != null) throw DomainException.Conflict("An account with this email already exists")
        val user = users.create(
            User(UUID.randomUUID(), normalized, name?.trim()?.takeIf { it.isNotEmpty() }?.take(120), hasher.hash(password), clock.instant()),
        )
        return AuthResult(user, issue(user))
    }

    suspend fun login(email: String, password: String): AuthResult {
        val user = users.findByEmail(email.trim().lowercase())
        // Same error for unknown email and wrong password: no account enumeration.
        if (user == null || !hasher.verify(password, user.passwordHash)) throw DomainException.Unauthorized("Invalid email or password")
        return AuthResult(user, issue(user))
    }

    suspend fun refresh(refreshToken: String): AuthResult {
        val userId = sessions.consume(refreshToken) ?: throw DomainException.Unauthorized("Session expired, please sign in again")
        val user = users.findById(userId) ?: throw DomainException.Unauthorized("Account no longer exists")
        return AuthResult(user, issue(user))
    }

    suspend fun logout(refreshToken: String?, userId: UUID, everywhere: Boolean) {
        if (everywhere) sessions.revokeAll(userId) else refreshToken?.let { sessions.revoke(it) }
    }

    suspend fun me(userId: UUID): User = users.findById(userId) ?: throw DomainException.NotFound("User not found")

    private suspend fun issue(user: User): TokenPair {
        val (access, ttl) = issuer.issue(user)
        return TokenPair(access, sessions.create(user.id), ttl)
    }

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

    private companion object {
        val EMAIL = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$")
    }
}
