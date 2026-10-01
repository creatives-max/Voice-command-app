package com.voicecontrol.infrastructure.kafka

import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.EventPublisher
import com.voicecontrol.domain.event.FlowDeleted
import com.voicecontrol.domain.event.FlowVersionSaved
import com.voicecontrol.domain.event.RunFinished
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaEventBusTest {
    private class Recorder(private val failFirst: Int = 0) : EventHandler {
        override val eventTypes = setOf(FlowVersionSaved.TYPE, FlowDeleted.TYPE, RunFinished.TYPE)
        val seen: MutableList<DomainEvent> = Collections.synchronizedList(mutableListOf())
        private var failures = 0

        override suspend fun handle(event: DomainEvent) {
            if (failures < failFirst) {
                failures++
                error("temporary failure")
            }
            seen += event
        }
    }

    @Test
    fun `events round-trip through Kafka, survive handler failures and keep their ids`() = runBlocking {
        KafkaContainer("apache/kafka:3.9.1").use { kafka ->
            kafka.start()
            val handler = Recorder(failFirst = 2)
            val bus = KafkaEventBus(kafka.bootstrapServers, listOf(handler), topic = "test.events", retryBackoff = Duration.ofMillis(50))
            bus.ensureTopics()
            bus.ensureTopics() // idempotent
            bus.start()
            try {
                val saved = FlowVersionSaved("f1", "u1", "com.app", 2, "sig", orgId = "o1")
                val run = RunFinished("r1", "u1", "f1", "Login", "COMPLETED", "MANUAL", orgId = "o1")
                bus.publish(saved)
                bus.publish(run)
                repeat(150) { if (handler.seen.size < 2) delay(100) }
                assertEquals(listOf(saved, run), handler.seen.toList())
            } finally {
                bus.close()
            }
        }
    }

    @Test
    fun `fallback publisher uses the second bus when the first fails`() = runBlocking {
        val received = mutableListOf<DomainEvent>()
        val failing = EventPublisher { error("broker down") }
        val fallback = EventPublisher { received += it }
        val event = FlowDeleted("f", "u", "com.app")
        FallbackEventPublisher(failing, fallback).publish(event)
        assertEquals(listOf<DomainEvent>(event), received)

        val primary = mutableListOf<DomainEvent>()
        FallbackEventPublisher({ primary += it }, fallback).publish(event)
        assertTrue(primary.single() == event && received.size == 1)
    }
}
