package com.voicecontrol.domain.event

import kotlinx.serialization.Serializable

/** Integration events published on the event bus (Redis Streams). */
@Serializable
sealed interface DomainEvent {
    val type: String
}

@Serializable
data class FlowVersionSaved(
    val flowId: String,
    val userId: String,
    val appPackage: String,
    val version: Int,
    val screenSignature: String,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "flow.version_saved"
    }
}

@Serializable
data class FlowDeleted(val flowId: String, val userId: String, val appPackage: String) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "flow.deleted"
    }
}

interface EventPublisher {
    suspend fun publish(event: DomainEvent)
}

/** Handles events delivered by the bus. Must be idempotent: delivery is at-least-once. */
interface EventHandler {
    val eventTypes: Set<String>
    suspend fun handle(event: DomainEvent)
}

/** Simple key-value cache port (Redis). */
interface Cache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String, ttlSeconds: Long)
    suspend fun delete(vararg keys: String)
    suspend fun deleteByPrefix(prefix: String)
}

/** Fixed-window rate limiter port. Returns true when the call is allowed. */
interface RateLimiter {
    suspend fun tryAcquire(key: String, limit: Int, windowSeconds: Long): Boolean
}
