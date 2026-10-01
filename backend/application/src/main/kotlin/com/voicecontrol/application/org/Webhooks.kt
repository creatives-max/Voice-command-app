package com.voicecontrol.application.org

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.FlowDeleted
import com.voicecontrol.domain.event.FlowVersionSaved
import com.voicecontrol.domain.event.RunFinished
import com.voicecontrol.domain.org.Actor
import com.voicecontrol.domain.org.AttemptResult
import com.voicecontrol.domain.org.AuditLog
import com.voicecontrol.domain.org.DeliveryStatus
import com.voicecontrol.domain.org.DueDelivery
import com.voicecontrol.domain.org.Role
import com.voicecontrol.domain.org.Webhook
import com.voicecontrol.domain.org.WebhookDelivery
import com.voicecontrol.domain.org.WebhookEvents
import com.voicecontrol.domain.org.WebhookRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sends one signed HTTP POST; implemented with an HTTP client in the infrastructure layer. */
fun interface WebhookSender {
    suspend fun post(url: String, headers: Map<String, String>, body: String): AttemptResult
}

/**
 * Which webhook URLs are allowed: https only (http with [allowHttp], for local development) and never
 * private, loopback or link-local addresses (unless [allowPrivate]), checked when saving and again
 * before every delivery so a DNS change can't point a webhook at internal services.
 */
class WebhookUrlPolicy(
    private val allowHttp: Boolean = false,
    private val allowPrivate: Boolean = false,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Null if the URL may be called, otherwise why not. */
    fun problem(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "Enter a valid URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return "Webhook URLs must use https://"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return "Enter a valid URL"
        if (uri.userInfo != null) return "Webhook URLs can't contain credentials"
        if (url.length > 2_000) return "URL is too long"
        if (allowPrivate) return null
        val addresses = runCatching { resolve(host) }.getOrNull() ?: return "Can't resolve $host"
        if (addresses.isEmpty()) return "Can't resolve $host"
        if (addresses.any { isInternal(it) }) return "Webhook URLs must point to a public address"
        return null
    }

    companion object {
        fun isInternal(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            return when (b.size) {
                // 100.64.0.0/10 (carrier-grade NAT), 0.0.0.0/8
                4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64 || b[0].toInt() == 0
                // fc00::/7 unique local
                16 -> (b[0].toInt() and 0xFE) == 0xFC
                else -> false
            }
        }
    }
}

/** HMAC signature sent as `X-VoiceControl-Signature: t=<unix seconds>,v1=<hex hmac>` over "<t>.<body>". */
object WebhookSignature {
    fun sign(secret: String, timestamp: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hex = mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$hex"
    }

    /** Receiver-side check (used by tests and documented for integrators). */
    fun verify(secret: String, header: String, body: String, now: Instant, tolerance: Duration = Duration.ofMinutes(5)): Boolean {
        val parts = header.split(',').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val t = parts["t"]?.toLongOrNull() ?: return false
        val v1 = parts["v1"] ?: return false
        if (kotlin.math.abs(now.epochSecond - t) > tolerance.seconds) return false
        val expected = sign(secret, t, body).substringAfter("v1=")
        return java.security.MessageDigest.isEqual(expected.toByteArray(), v1.toByteArray())
    }
}

/** Managing an organization's webhooks (admins only). */
class WebhookService(
    private val repo: WebhookRepository,
    private val access: OrgAccess,
    private val audit: AuditLog,
    private val policy: WebhookUrlPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun list(userId: UUID, orgId: UUID): List<Webhook> {
        access.require(orgId, userId, Role.ADMIN)
        return repo.list(orgId)
    }

    suspend fun create(userId: UUID, orgId: UUID, url: String, events: Set<String>): Webhook {
        access.require(orgId, userId, Role.ADMIN)
        if (repo.list(orgId).size >= MAX_PER_ORG) throw DomainException.Validation("An organization can have at most $MAX_PER_ORG webhooks")
        val hook = repo.create(
            Webhook(UUID.randomUUID(), orgId, validUrl(url), "whsec_" + Secrets.token(24), validEvents(events), true, userId, clock.instant()),
        )
        audit.record(orgId, Actor(userId), "webhook.created", "webhook", hook.id.toString(), mapOf("url" to hook.url, "events" to hook.events.sorted().joinToString(",")))
        return hook
    }

    suspend fun update(userId: UUID, orgId: UUID, id: UUID, url: String?, events: Set<String>?, active: Boolean?): Webhook {
        access.require(orgId, userId, Role.ADMIN)
        val hook = repo.find(orgId, id) ?: throw DomainException.NotFound("Webhook not found")
        val next = hook.copy(url = url?.let(::validUrl) ?: hook.url, events = events?.let(::validEvents) ?: hook.events, active = active ?: hook.active)
        val saved = repo.update(next) ?: throw DomainException.NotFound("Webhook not found")
        audit.record(orgId, Actor(userId), "webhook.updated", "webhook", id.toString(), mapOf("url" to saved.url, "active" to saved.active.toString()))
        return saved
    }

    /** Replaces the signing secret; the new one is returned once. */
    suspend fun rotateSecret(userId: UUID, orgId: UUID, id: UUID): Webhook {
        access.require(orgId, userId, Role.ADMIN)
        val hook = repo.find(orgId, id) ?: throw DomainException.NotFound("Webhook not found")
        val saved = repo.update(hook.copy(secret = "whsec_" + Secrets.token(24))) ?: throw DomainException.NotFound("Webhook not found")
        audit.record(orgId, Actor(userId), "webhook.secret_rotated", "webhook", id.toString(), emptyMap())
        return saved
    }

    suspend fun delete(userId: UUID, orgId: UUID, id: UUID) {
        access.require(orgId, userId, Role.ADMIN)
        if (!repo.delete(orgId, id)) throw DomainException.NotFound("Webhook not found")
        audit.record(orgId, Actor(userId), "webhook.deleted", "webhook", id.toString(), emptyMap())
    }

    suspend fun deliveries(userId: UUID, orgId: UUID, id: UUID, limit: Int): List<WebhookDelivery> {
        access.require(orgId, userId, Role.ADMIN)
        repo.find(orgId, id) ?: throw DomainException.NotFound("Webhook not found")
        return repo.deliveries(orgId, id, limit.coerceIn(1, 100))
    }

    /** Sends a delivery again (as a new attempt, right away). */
    suspend fun redeliver(userId: UUID, orgId: UUID, deliveryId: UUID): WebhookDelivery {
        access.require(orgId, userId, Role.ADMIN)
        if (!repo.retry(orgId, deliveryId, clock.instant())) throw DomainException.NotFound("Delivery not found")
        return repo.delivery(orgId, deliveryId) ?: throw DomainException.NotFound("Delivery not found")
    }

    /** Queues a `ping` event to check the endpoint. */
    suspend fun ping(userId: UUID, orgId: UUID, id: UUID): WebhookDelivery {
        access.require(orgId, userId, Role.ADMIN)
        val hook = repo.find(orgId, id) ?: throw DomainException.NotFound("Webhook not found")
        val eventId = UUID.randomUUID().toString()
        val now = clock.instant()
        val payload = WebhookPayloads.envelope(eventId, WebhookEvents.PING, orgId.toString(), now, buildJsonObject { put("webhookId", hook.id.toString()) })
        val delivery = WebhookDelivery(UUID.randomUUID(), hook.id, eventId, WebhookEvents.PING, payload, DeliveryStatus.PENDING, 0, now, null, null, now, null)
        repo.enqueue(delivery)
        return delivery
    }

    private fun validUrl(url: String): String {
        policy.problem(url)?.let { throw DomainException.Validation(it) }
        return url.trim()
    }

    private fun validEvents(events: Set<String>): Set<String> {
        if (events.isEmpty()) throw DomainException.Validation("Choose at least one event")
        val unknown = events - WebhookEvents.ALL
        if (unknown.isNotEmpty()) throw DomainException.Validation("Unknown events: ${unknown.joinToString(", ")}")
        return events
    }

    companion object {
        const val MAX_PER_ORG = 20
    }
}

/** JSON bodies sent to webhooks: `{id, type, createdAt, orgId, data}`. Never contains spoken or typed values. */
object WebhookPayloads {
    fun envelope(eventId: String, type: String, orgId: String, at: Instant, data: JsonObject): String =
        buildJsonObject {
            put("id", eventId)
            put("type", type)
            put("createdAt", at.toString())
            put("orgId", orgId)
            put("data", data)
        }.toString()

    fun data(event: DomainEvent): JsonObject? = when (event) {
        is FlowVersionSaved -> buildJsonObject {
            put("flowId", event.flowId)
            put("appPackage", event.appPackage)
            put("version", event.version)
            put("userId", event.userId)
        }
        is FlowDeleted -> buildJsonObject {
            put("flowId", event.flowId)
            put("appPackage", event.appPackage)
            put("userId", event.userId)
        }
        is RunFinished -> buildJsonObject {
            put("runId", event.requestId)
            put("flowId", event.flowId)
            put("flowName", event.flowName)
            put("status", event.status)
            put("source", event.source)
            put("userId", event.userId)
        }
    }
}

/** Event handler: queues a delivery for every active webhook of the event's organization that subscribes to it. */
class WebhookDispatcher(private val repo: WebhookRepository, private val clock: Clock = Clock.systemUTC()) : EventHandler {
    override val eventTypes = setOf(FlowVersionSaved.TYPE, FlowDeleted.TYPE, RunFinished.TYPE)

    override suspend fun handle(event: DomainEvent) {
        val orgId = event.orgId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val data = WebhookPayloads.data(event) ?: return
        val now = clock.instant()
        val payload = WebhookPayloads.envelope(event.eventId, event.type, orgId.toString(), now, data)
        repo.subscribers(orgId, event.type).forEach { hook ->
            repo.enqueue(WebhookDelivery(UUID.randomUUID(), hook.id, event.eventId, event.type, payload, DeliveryStatus.PENDING, 0, now, null, null, now, null))
        }
    }
}

/**
 * Sends due deliveries. Failed attempts are retried after 30 s, 2 min, 10 min, 30 min, 1 h and 3 h,
 * then marked failed. Safe on many replicas: deliveries are claimed with `FOR UPDATE SKIP LOCKED`.
 */
class WebhookWorker(
    private val repo: WebhookRepository,
    private val sender: WebhookSender,
    private val policy: WebhookUrlPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(WebhookWorker::class.java)

    suspend fun tick(now: Instant = clock.instant(), limit: Int = 20): Int =
        repo.processDue(now, limit, ::attempt) { attempts -> BACKOFF.getOrNull(attempts - 1)?.let { now.plus(it) } }

    private suspend fun attempt(due: DueDelivery): AttemptResult {
        policy.problem(due.url)?.let { return AttemptResult(null, it) }
        val timestamp = clock.instant().epochSecond
        val headers = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "VoiceControl-Webhooks/1",
            "X-VoiceControl-Event" to due.delivery.eventType,
            "X-VoiceControl-Delivery" to due.delivery.id.toString(),
            "X-VoiceControl-Timestamp" to timestamp.toString(),
            "X-VoiceControl-Signature" to WebhookSignature.sign(due.secret, timestamp, due.delivery.payload),
        )
        return runCatching { sender.post(due.url, headers, due.delivery.payload) }
            .getOrElse { AttemptResult(null, it.message ?: it::class.simpleName) }
            .also { if (!it.ok) log.info("Webhook delivery {} failed: {} {}", due.delivery.id, it.statusCode, it.error) }
    }

    companion object {
        val BACKOFF: List<Duration> = listOf(
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3),
        )
    }
}
