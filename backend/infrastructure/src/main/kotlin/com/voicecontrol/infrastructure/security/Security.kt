package com.voicecontrol.infrastructure.security

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.voicecontrol.domain.user.AccessTokenIssuer
import com.voicecontrol.domain.user.PasswordHasher
import com.voicecontrol.domain.user.User
import com.voicecontrol.infrastructure.config.AppConfig
import java.time.Clock
import java.util.Date

class BcryptPasswordHasher(private val cost: Int = 12) : PasswordHasher {
    override fun hash(password: String): String = BCrypt.withDefaults().hashToString(cost, password.toCharArray())
    override fun verify(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}

/** HS256 access tokens: `sub` = user id, short TTL; refresh tokens are server-side sessions in Redis. */
class JwtIssuer(private val config: AppConfig.JwtConfig, private val clock: Clock = Clock.systemUTC()) : AccessTokenIssuer {
    val algorithm: Algorithm = Algorithm.HMAC256(config.secret)

    override fun issue(user: User, sessionId: String?): Pair<String, Long> {
        val now = clock.instant()
        val token = JWT.create()
            .apply { if (sessionId != null) withClaim(SESSION_CLAIM, sessionId) }
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(user.id.toString())
            .withClaim("email", user.email)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(config.accessTtlSeconds)))
            .sign(algorithm)
        return token to config.accessTtlSeconds
    }

    companion object {
        const val SESSION_CLAIM = "sid"
    }
}
