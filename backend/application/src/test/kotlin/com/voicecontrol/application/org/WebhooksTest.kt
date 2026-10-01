package com.voicecontrol.application.org

import com.voicecontrol.domain.event.FlowVersionSaved
import com.voicecontrol.domain.event.RunFinished
import com.voicecontrol.domain.org.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhooksTest {
    private fun policy(vararg ips: String) = WebhookUrlPolicy(resolve = { ips.map { InetAddress.getByName(it) } })

    @Test
    fun `url policy allows public https only`() {
        assertNull(policy("93.184.216.34").problem("https://hooks.example.com/voice"))
        assertNotNull(policy("93.184.216.34").problem("http://hooks.example.com/voice"))
        assertNotNull(policy("93.184.216.34").problem("ftp://hooks.example.com"))
        assertNotNull(policy("93.184.216.34").problem("https://user:pw@hooks.example.com"))
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1").forEach {
            assertNotNull(policy(it).problem("https://internal.example.com"), it)
        }
        // Any internal address among several resolved ones is rejected.
        assertNotNull(policy("93.184.216.34", "10.0.0.1").problem("https://mixed.example.com"))
        assertNull(WebhookUrlPolicy(allowHttp = true, allowPrivate = true).problem("http://localhost:9000/hook"))
    }

    @Test
    fun `signatures verify and reject tampering and old timestamps`() {
        val now = Instant.parse("2026-10-01T10:00:00Z")
        val header = WebhookSignature.sign("whsec_abc", now.epochSecond, """{"a":1}""")
        assertTrue(header.startsWith("t=${now.epochSecond},v1="))
        assertTrue(WebhookSignature.verify("whsec_abc", header, """{"a":1}""", now))
        assertFalse(WebhookSignature.verify("whsec_abc", header, """{"a":2}""", now))
        assertFalse(WebhookSignature.verify("whsec_other", header, """{"a":1}""", now))
        assertFalse(WebhookSignature.verify("whsec_abc", header, """{"a":1}""", now.plus(Duration.ofMinutes(6))))
        assertFalse(WebhookSignature.verify("whsec_abc", "garbage", """{"a":1}""", now))
    }

    @Test
    fun `payloads carry ids but no values`() {
        val saved = FlowVersionSaved("f1", "u1", "com.app", 3, "sig", orgId = "o1")
        val body = WebhookPayloads.envelope(saved.eventId, saved.type, "o1", Instant.EPOCH, WebhookPayloads.data(saved)!!)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("flow.version_saved", json["type"]!!.jsonPrimitive.content)
        assertEquals(saved.eventId, json["id"]!!.jsonPrimitive.content)
        assertEquals("3", json["data"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertFalse(body.contains("sig"))
        val run = WebhookPayloads.data(RunFinished("r1", "u1", null, "Login", "COMPLETED", "MANUAL", "o1"))!!
        assertEquals("COMPLETED", run["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `backoff schedule and roles`() {
        assertEquals(6, WebhookWorker.BACKOFF.size)
        assertEquals(Duration.ofSeconds(30), WebhookWorker.BACKOFF.first())
        assertTrue(Role.ADMIN.atLeast(Role.EDITOR) && Role.EDITOR.atLeast(Role.VIEWER) && !Role.VIEWER.atLeast(Role.EDITOR))
        assertEquals(64, Secrets.sha256("x").length)
    }
}
