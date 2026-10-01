package com.voicecontrol.infrastructure.redis

import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.event.RateLimiter
import com.voicecontrol.domain.user.SessionStore
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import kotlinx.coroutines.future.await
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class RedisCache(private val redis: RedisConnections) : Cache {
    override suspend fun get(key: String): String? = redis.commands.get(key).await()

    override suspend fun put(key: String, value: String, ttlSeconds: Long) {
        redis.commands.set(key, value, SetArgs.Builder.ex(ttlSeconds)).await()
    }

    override suspend fun delete(vararg keys: String) {
        if (keys.isNotEmpty()) redis.commands.del(*keys).await()
    }

    override suspend fun deleteByPrefix(prefix: String) {
        var cursor = ScanCursor.INITIAL
        do {
            val page = redis.commands.scan(cursor, ScanArgs.Builder.matches("$prefix*").limit(500)).await()
            if (page.keys.isNotEmpty()) redis.commands.del(*page.keys.toTypedArray()).await()
            cursor = page
        } while (!page.isFinished)
    }
}

/**
 * Refresh-token sessions. Only SHA-256 hashes of tokens are stored; refreshing consumes the old token
 * (rotation), so a stolen refresh token stops working as soon as the real client refreshes.
 */
class RedisSessionStore(private val redis: RedisConnections, private val ttlSeconds: Long) : SessionStore {
    private val random = SecureRandom()

    override suspend fun create(userId: UUID): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = hash(token)
        redis.commands.set(tokenKey(hash), userId.toString(), SetArgs.Builder.ex(ttlSeconds)).await()
        redis.commands.sadd(userKey(userId), hash).await()
        redis.commands.expire(userKey(userId), ttlSeconds).await()
        return token
    }

    override suspend fun consume(refreshToken: String): UUID? {
        val hash = hash(refreshToken)
        val userId = redis.commands.getdel(tokenKey(hash)).await() ?: return null
        val id = runCatching { UUID.fromString(userId) }.getOrNull() ?: return null
        redis.commands.srem(userKey(id), hash).await()
        return id
    }

    override suspend fun revoke(refreshToken: String) {
        consume(refreshToken)
    }

    override suspend fun revokeAll(userId: UUID) {
        val hashes = redis.commands.smembers(userKey(userId)).await()
        if (hashes.isNotEmpty()) redis.commands.del(*hashes.map(::tokenKey).toTypedArray()).await()
        redis.commands.del(userKey(userId)).await()
    }

    private fun tokenKey(hash: String) = "vc:refresh:$hash"
    private fun userKey(userId: UUID) = "vc:user-sessions:$userId"

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Fixed-window counter shared by all backend replicas. */
class RedisRateLimiter(private val redis: RedisConnections) : RateLimiter {
    override suspend fun tryAcquire(key: String, limit: Int, windowSeconds: Long): Boolean {
        val window = System.currentTimeMillis() / 1000 / windowSeconds
        val redisKey = "vc:rl:$key:$window"
        val count = redis.commands.incr(redisKey).await()
        if (count == 1L) redis.commands.expire(redisKey, windowSeconds + 1).await()
        return count <= limit
    }
}

/**
 * Presence per flow: a sorted set of user ids scored by last heartbeat plus a hash with display data.
 * Entries older than [window] are dropped on every heartbeat; idle flows' keys expire on their own.
 */
class RedisPresenceStore(
    private val redis: RedisConnections,
    private val window: java.time.Duration = java.time.Duration.ofSeconds(45),
) : com.voicecontrol.domain.collab.PresenceStore {
    private fun zkey(flowId: java.util.UUID) = "vc:presence:$flowId"
    private fun hkey(flowId: java.util.UUID) = "vc:presence:$flowId:info"

    override suspend fun heartbeat(flowId: java.util.UUID, presence: com.voicecontrol.domain.collab.Presence): List<com.voicecontrol.domain.collab.Presence> {
        val now = presence.seenAt.toEpochMilli()
        val c = redis.commands
        c.zadd(zkey(flowId), now.toDouble(), presence.userId.toString()).await()
        c.hset(hkey(flowId), presence.userId.toString(), "${if (presence.editing) 1 else 0}|${presence.name}").await()
        c.zremrangebyscore(zkey(flowId), io.lettuce.core.Range.create(0.0, (now - window.toMillis()).toDouble())).await()
        val ttl = window.seconds * 4
        c.expire(zkey(flowId), ttl).await()
        c.expire(hkey(flowId), ttl).await()
        val members = c.zrangeWithScores(zkey(flowId), 0, -1).await()
        if (members.isEmpty()) return emptyList()
        val info = c.hmget(hkey(flowId), *members.map { it.value }.toTypedArray()).await().associate { it.key to it.getValueOrElse(null) }
        return members.map { m ->
            val parts = (info[m.value] ?: "0|Someone").split('|', limit = 2)
            val editing = parts[0] == "1"
            val name = parts.getOrElse(1) { "Someone" }
            com.voicecontrol.domain.collab.Presence(java.util.UUID.fromString(m.value), name, editing, java.time.Instant.ofEpochMilli(m.score.toLong()))
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun leave(flowId: java.util.UUID, userId: java.util.UUID) {
        redis.commands.zrem(zkey(flowId), userId.toString()).await()
        redis.commands.hdel(hkey(flowId), userId.toString()).await()
    }
}
