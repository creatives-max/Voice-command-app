package com.voicecontrol.infrastructure.redis

import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.EventPublisher
import io.lettuce.core.Consumer
import io.lettuce.core.RedisBusyException
import io.lettuce.core.StreamMessage
import io.lettuce.core.XAddArgs
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Event bus on Redis Streams.
 *
 * - [publish] appends to the stream (capped with approximate MAXLEN).
 * - [start] runs a consumer-group loop; each replica is a separate consumer so work is shared.
 * - Messages are ACKed only after every handler succeeds; failed ones stay pending and are
 *   re-claimed (XAUTOCLAIM) after [retryAfter], giving at-least-once delivery. Messages that keep
 *   failing are moved to a dead-letter stream after [maxDeliveries].
 */
class RedisStreamEventBus(
    private val redis: RedisConnections,
    private val handlers: List<EventHandler>,
    private val stream: String = "vc:events",
    private val group: String = "flow-processor",
    private val consumerName: String = "backend-" + java.util.UUID.randomUUID().toString().take(8),
    private val retryAfter: Duration = Duration.ofSeconds(30),
    private val maxDeliveries: Int = 5,
) : EventPublisher, AutoCloseable {

    private val log = LoggerFactory.getLogger(RedisStreamEventBus::class.java)
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val attempts = mutableMapOf<String, Int>()
    private val tracer = GlobalOpenTelemetry.getTracer("voicecontrol.events")

    override suspend fun publish(event: DomainEvent) {
        val body = mapOf("type" to event.type, "payload" to json.encodeToString(DomainEvent.serializer(), event))
        redis.commands.xadd(stream, XAddArgs.Builder.maxlen(100_000).approximateTrimming(), body).await()
    }

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            val connection = redis.dedicated()
            val commands = connection.async()
            try {
                commands.xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), group, XGroupCreateArgs.Builder.mkstream()).await()
            } catch (e: Exception) {
                if (e.cause !is RedisBusyException && e !is RedisBusyException && e.message?.contains("BUSYGROUP") != true) throw e
            }
            val consumer = Consumer.from(group, consumerName)
            var lastClaim = 0L
            while (isActive) {
                try {
                    if (System.currentTimeMillis() - lastClaim > retryAfter.toMillis()) {
                        val claimed = commands.xautoclaim(stream, XAutoClaimArgs.Builder.xautoclaim(consumer, retryAfter, "0").count(50)).await()
                        claimed.messages.forEach { process(commands, it) }
                        lastClaim = System.currentTimeMillis()
                    }
                    val messages = commands.xreadgroup(
                        consumer,
                        XReadArgs.Builder.block(Duration.ofSeconds(2)).count(20),
                        XReadArgs.StreamOffset.lastConsumed(stream),
                    ).await()
                    messages.forEach { process(commands, it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Event loop error: {}", e.message)
                    delay(1_000)
                }
            }
            connection.close()
        }
    }

    private suspend fun process(commands: io.lettuce.core.api.async.RedisAsyncCommands<String, String>, message: StreamMessage<String, String>) {
        val type = message.body["type"] ?: return ack(commands, message.id)
        val event = runCatching { json.decodeFromString(DomainEvent.serializer(), message.body["payload"].orEmpty()) }.getOrNull()
        if (event == null) {
            log.warn("Dropping undecodable event {} ({})", message.id, type)
            return ack(commands, message.id)
        }
        val span = tracer.spanBuilder("event $type").startSpan()
        try {
            handlers.filter { type in it.eventTypes }.forEach { it.handle(event) }
            ack(commands, message.id)
            attempts.remove(message.id)
        } catch (e: Exception) {
            span.recordException(e)
            val n = (attempts[message.id] ?: 0) + 1
            attempts[message.id] = n
            log.warn("Handler failed for {} ({}), attempt {}: {}", message.id, type, n, e.message)
            if (n >= maxDeliveries) {
                commands.xadd("$stream:dead", message.body + ("error" to (e.message ?: "error"))).await()
                ack(commands, message.id)
                attempts.remove(message.id)
            }
        } finally {
            span.end()
        }
    }

    private suspend fun ack(commands: io.lettuce.core.api.async.RedisAsyncCommands<String, String>, id: String) {
        commands.xack(stream, group, id).await()
    }

    /** For tests and health checks: number of messages delivered but not yet acknowledged. */
    suspend fun pendingCount(): Long = redis.commands.xpending(stream, group).await().count

    override fun close() {
        kotlinx.coroutines.runBlocking { loop?.cancelAndJoin() }
        loop = null
    }
}
