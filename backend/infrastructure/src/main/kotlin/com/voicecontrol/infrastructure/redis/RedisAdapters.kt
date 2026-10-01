package com.voicecontrol.infrastructure.redis

import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.event.RateLimiter
import com.voicecontrol.domain.user.ConsumedSession
import com.voicecontrol.domain.user.SessionInfo
import com.voicecontrol.domain.user.SessionStore
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import kotlinx.coroutines.future.await
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
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
 * (rotation), so a stolen refresh token stops working as soon as the real client refreshes. Each session
 * keeps an id, a client description and its times across rotations so people can see and end them.
 *
 * Keys: `vc:refresh:<hash>` → "<userId>|<sessionId>"; `vc:session:<id>` → hash (user, client, created,
 * used, token); `vc:user-sessions:<userId>` → token hashes; `vc:user-session-ids:<userId>` → session ids.
 */
class RedisSessionStore(
    private val redis: RedisConnections,
    private val ttlSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
) : SessionStore {
    private val random = SecureRandom()

    override suspend fun create(userId: UUID, sessionId: String, client: String?, createdAt: Instant?): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = hash(token)
        val now = clock.instant()
        val c = redis.commands
        c.set(tokenKey(hash), "$userId|$sessionId", SetArgs.Builder.ex(ttlSeconds)).await()
        c.sadd(userKey(userId), hash).await()
        c.expire(userKey(userId), ttlSeconds).await()
        val meta = buildMap {
            put("user", userId.toString())
            put("created", (createdAt ?: now).toEpochMilli().toString())
            put("used", now.toEpochMilli().toString())
            put("token", hash)
            client?.let { put("client", it.take(MAX_CLIENT)) }
        }
        c.hset(sessionKey(sessionId), meta).await()
        c.expire(sessionKey(sessionId), ttlSeconds).await()
        c.sadd(sessionIdsKey(userId), sessionId).await()
        c.expire(sessionIdsKey(userId), ttlSeconds).await()
        return token
    }

    override suspend fun consume(refreshToken: String): ConsumedSession? {
        val hash = hash(refreshToken)
        val value = redis.commands.getdel(tokenKey(hash)).await() ?: return null
        // Tokens issued before sessions had ids hold only the user id.
        val userPart = value.substringBefore('|')
        val id = runCatching { UUID.fromString(userPart) }.getOrNull() ?: return null
        redis.commands.srem(userKey(id), hash).await()
        val sessionId = value.substringAfter('|', "").ifEmpty { UUID.randomUUID().toString() }
        val meta = redis.commands.hgetall(sessionKey(sessionId)).await().orEmpty()
        val createdAt = meta["created"]?.toLongOrNull()?.let(Instant::ofEpochMilli)
        return ConsumedSession(id, sessionId, meta["client"], createdAt)
    }

    override suspend fun revoke(refreshToken: String) {
        val session = consume(refreshToken) ?: return
        forget(session.userId, session.sessionId)
    }

    override suspend fun revokeAll(userId: UUID) {
        val c = redis.commands
        val hashes = c.smembers(userKey(userId)).await()
        if (hashes.isNotEmpty()) c.del(*hashes.map(::tokenKey).toTypedArray()).await()
        val ids = c.smembers(sessionIdsKey(userId)).await()
        if (ids.isNotEmpty()) c.del(*ids.map(::sessionKey).toTypedArray()).await()
        c.del(userKey(userId), sessionIdsKey(userId)).await()
    }

    override suspend fun list(userId: UUID): List<SessionInfo> {
        val c = redis.commands
        val result = mutableListOf<SessionInfo>()
        for (id in c.smembers(sessionIdsKey(userId)).await()) {
            val meta = c.hgetall(sessionKey(id)).await().orEmpty()
            val token = meta["token"]
            // Sessions whose refresh token expired or was used up without a new one are gone.
            val alive = meta["user"] == userId.toString() && token != null && c.exists(tokenKey(token)).await() > 0
            if (!alive) {
                c.srem(sessionIdsKey(userId), id).await()
                c.del(sessionKey(id)).await()
                continue
            }
            val created = meta["created"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: continue
            val used = meta["used"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: created
            result += SessionInfo(id, meta["client"], created, used)
        }
        return result.sortedByDescending { it.lastUsedAt }
    }

    override suspend fun revokeSession(userId: UUID, sessionId: String): Boolean {
        val meta = redis.commands.hgetall(sessionKey(sessionId)).await().orEmpty()
        if (meta["user"] != userId.toString()) return false
        meta["token"]?.let { token ->
            redis.commands.del(tokenKey(token)).await()
            redis.commands.srem(userKey(userId), token).await()
        }
        forget(userId, sessionId)
        return true
    }

    private suspend fun forget(userId: UUID, sessionId: String) {
        redis.commands.del(sessionKey(sessionId)).await()
        redis.commands.srem(sessionIdsKey(userId), sessionId).await()
    }

    private fun tokenKey(hash: String) = "vc:refresh:$hash"
    private fun userKey(userId: UUID) = "vc:user-sessions:$userId"
    private fun sessionKey(sessionId: String) = "vc:session:$sessionId"
    private fun sessionIdsKey(userId: UUID) = "vc:user-session-ids:$userId"

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CLIENT = 120
    }
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
