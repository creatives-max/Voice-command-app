package com.voicecontrol.domain.event

import kotlinx.serialization.Serializable

/**
 * Integration events published on the event bus (Kafka, with Redis Streams as fallback).
 * [eventId] is unique per event so consumers (webhooks) can deduplicate at-least-once deliveries.
 */
@Serializable
sealed interface DomainEvent {
    val type: String
    val eventId: String
    /** Organization the event belongs to, if any (webhooks are per organization). */
    val orgId: String?
}

private fun newEventId() = java.util.UUID.randomUUID().toString()

@Serializable
data class FlowVersionSaved(
    val flowId: String,
    val userId: String,
    val appPackage: String,
    val version: Int,
    val screenSignature: String,
    override val orgId: String? = null,
    override val eventId: String = newEventId(),
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "flow.version_saved"
    }
}

@Serializable
data class FlowDeleted(
    val flowId: String,
    val userId: String,
    val appPackage: String,
    override val orgId: String? = null,
    override val eventId: String = newEventId(),
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "flow.deleted"
    }
}

/** A remote run (run now, schedule, app open) reached a final status. */
@Serializable
data class RunFinished(
    val requestId: String,
    val userId: String,
    val flowId: String?,
    val flowName: String,
    val status: String,
    val source: String,
    override val orgId: String? = null,
    override val eventId: String = newEventId(),
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "run.finished"
    }
}

fun interface EventPublisher {
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
