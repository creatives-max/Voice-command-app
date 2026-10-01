package com.voicecontrol.infrastructure.kafka

import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.EventPublisher
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Event bus on Kafka.
 *
 * - [publish] sends to [topic] with an idempotent producer (acks=all), keyed by organization (or user)
 *   so one owner's events stay ordered within a partition.
 * - [start] runs a consumer in group [group]; every replica joins the group so partitions are shared.
 *   Offsets are committed only after all handlers succeed. A failing record is retried with backoff up to
 *   [maxDeliveries] times and then copied to `<topic>.dead`, so one bad event never blocks a partition.
 */
class KafkaEventBus(
    private val bootstrapServers: String,
    private val handlers: List<EventHandler>,
    private val topic: String = "vc.events",
    private val group: String = "flow-processor",
    private val partitions: Int = 6,
    private val maxDeliveries: Int = 5,
    private val retryBackoff: Duration = Duration.ofSeconds(1),
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : EventPublisher, AutoCloseable {

    private val log = LoggerFactory.getLogger(KafkaEventBus::class.java)
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind"; encodeDefaults = true }
    private val tracer = GlobalOpenTelemetry.getTracer("voicecontrol.events")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var consumer: KafkaConsumer<String, String>? = null

    private val producer: KafkaProducer<String, String> by lazy {
        KafkaProducer(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.ACKS_CONFIG, "all")
                put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                put(ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeout.toMillis())
                put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, sendTimeout.toMillis().toInt())
                put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (sendTimeout.toMillis() * 2).toInt())
                put(ProducerConfig.LINGER_MS_CONFIG, 5)
                put(ProducerConfig.CLIENT_ID_CONFIG, "voicecontrol-backend")
            },
            StringSerializer(),
            StringSerializer(),
        )
    }

    /** Creates the topics if they don't exist (no-op when they do, or when the broker auto-creates them). */
    fun ensureTopics() {
        AdminClient.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers, "request.timeout.ms" to sendTimeout.toMillis().toInt())).use { admin ->
            listOf(NewTopic(topic, partitions, 1.toShort()), NewTopic("$topic.dead", 1, 1.toShort())).forEach { t ->
                try {
                    admin.createTopics(listOf(t)).all().get(sendTimeout.toMillis() * 2, TimeUnit.MILLISECONDS)
                } catch (e: ExecutionException) {
                    if (e.cause !is TopicExistsException) throw e
                }
            }
        }
    }

    override suspend fun publish(event: DomainEvent) {
        val record = ProducerRecord(topic, event.orgId ?: keyOf(event), json.encodeToString(DomainEvent.serializer(), event))
        record.headers().add("type", event.type.toByteArray())
        suspendCancellableCoroutine { cont ->
            try {
                producer.send(record) { _, error -> if (error == null) cont.resume(Unit) else cont.resumeWithException(error) }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            val c = KafkaConsumer(
                Properties().apply {
                    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                    put(ConsumerConfig.GROUP_ID_CONFIG, group)
                    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50)
                },
                StringDeserializer(),
                StringDeserializer(),
            )
            consumer = c
            try {
                c.subscribe(listOf(topic))
                while (isActive) {
                    val records = try {
                        c.poll(Duration.ofSeconds(1))
                    } catch (e: WakeupException) {
                        break
                    } catch (e: Exception) {
                        log.warn("Kafka poll failed: {}", e.message)
                        delay(1_000)
                        continue
                    }
                    for (record in records) {
                        process(record)
                        c.commitSync(mapOf(TopicPartition(record.topic(), record.partition()) to OffsetAndMetadata(record.offset() + 1)))
                    }
                }
            } finally {
                runCatching { c.close(Duration.ofSeconds(5)) }
                consumer = null
            }
        }
    }

    private suspend fun process(record: ConsumerRecord<String, String>) {
        val event = runCatching { json.decodeFromString(DomainEvent.serializer(), record.value()) }.getOrNull()
        if (event == null) {
            log.warn("Dropping undecodable event at {}-{}@{}", record.topic(), record.partition(), record.offset())
            return
        }
        val span = tracer.spanBuilder("event ${event.type}").startSpan()
        try {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    handlers.filter { event.type in it.eventTypes }.forEach { it.handle(event) }
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    span.recordException(e)
                    log.warn("Handler failed for {} ({}), attempt {}: {}", event.eventId, event.type, attempt, e.message)
                    if (attempt >= maxDeliveries) {
                        val dead = ProducerRecord("$topic.dead", record.key(), record.value())
                        dead.headers().add("error", (e.message ?: "error").take(500).toByteArray())
                        runCatching { producer.send(dead).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS) }
                            .onFailure { log.error("Could not dead-letter event {}", event.eventId, it) }
                        return
                    }
                    delay(retryBackoff.toMillis() * attempt)
                }
            }
        } finally {
            span.end()
        }
    }

    private fun keyOf(event: DomainEvent): String? = when (event) {
        is com.voicecontrol.domain.event.FlowVersionSaved -> event.userId
        is com.voicecontrol.domain.event.FlowDeleted -> event.userId
        is com.voicecontrol.domain.event.RunFinished -> event.userId
    }

    override fun close() {
        consumer?.wakeup()
        runBlocking { loop?.cancelAndJoin() }
        loop = null
        runCatching { producer.close(Duration.ofSeconds(5)) }
    }
}

/**
 * Publishes to [primary] (Kafka) and falls back to [fallback] (Redis Streams) when Kafka can't take the
 * event, so flow events are never lost while Kafka is down. Both buses are consumed by the same handlers,
 * and handlers are idempotent (webhook deliveries are unique per event id).
 */
class FallbackEventPublisher(private val primary: EventPublisher, private val fallback: EventPublisher) : EventPublisher {
    private val log = LoggerFactory.getLogger(FallbackEventPublisher::class.java)

    override suspend fun publish(event: DomainEvent) {
        try {
            primary.publish(event)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Primary event bus failed ({}); publishing {} to the fallback queue", e.message, event.eventId)
            fallback.publish(event)
        }
    }
}
