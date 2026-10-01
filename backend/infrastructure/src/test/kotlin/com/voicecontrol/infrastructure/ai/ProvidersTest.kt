package com.voicecontrol.infrastructure.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.IntentKind
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.ai.ScreenContext
import com.voicecontrol.domain.ai.ScreenElement
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.common.DomainException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProvidersTest {
    private val screen = ScreenContext("com.shop", elements = listOf(ScreenElement("phone", ElementKind.TEXT_FIELD, "Mobile", FieldType.PHONE)))
    private val command = InterpretCommand(screen, "phone", "mera number nau aath saat hai", Language.HINGLISH)

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val received = mutableListOf<Pair<String?, String>>()

    @AfterTest fun stop() { server?.stop(100, 100) }

    private fun fakeAnthropic(stopReason: String = "end_turn", text: String): String {
        val port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, port = port) {
            routing {
                post("/v1/messages") {
                    received += call.request.header("anthropic-beta") to call.receiveText()
                    val body = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                        "content":[{"type":"text","text":${Json.encodeToString(String.serializer(), text)}}],
                        "stop_reason":"$stopReason","stop_sequence":null,
                        "usage":{"input_tokens":10,"output_tokens":5}}"""
                    call.respondText(body, ContentType.Application.Json)
                }
            }
        }.start()
        return "http://127.0.0.1:$port"
    }

    private fun anthropic(baseUrl: String) = AnthropicProvider(
        AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).maxRetries(0).build(),
        "claude-opus-5-5",
    )

    @Test
    fun `anthropic provider sends structured output request and parses the result`() = runBlocking {
        val url = fakeAnthropic(text = """{"intent":"FILL","targetId":"phone","value":"987","extraFills":[],"reply":"","confidence":0.95}""")
        val result = anthropic(url).interpret(command)
        assertEquals(IntentKind.FILL, result.intent)
        assertEquals("987", result.value)
        assertEquals("anthropic", result.source)

        val (beta, body) = received.single()
        assertTrue(beta!!.contains("server-side-fallback-2026-07-01"))
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("claude-opus-5-5", json["model"]!!.jsonPrimitive.content)
        assertEquals("default", json["fallbacks"]!!.jsonPrimitive.content)
        val outputConfig = json["output_config"]!!.jsonObject
        assertEquals("low", outputConfig["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", outputConfig["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic refusal becomes an upstream error`() {
        val url = fakeAnthropic(stopReason = "refusal", text = "")
        assertFailsWith<DomainException.Upstream> { runBlocking { anthropic(url).interpret(command) } }
    }

    @Test
    fun `anthropic vision sends the image`() = runBlocking {
        val url = fakeAnthropic(text = """{"elements":[{"id":"e1","kind":"BUTTON","label":"Pay","fieldType":"","x":10,"y":20,"width":100,"height":40}]}""")
        val result = anthropic(url).detectElements(VisionCommand("com.pay", byteArrayOf(1, 2, 3), 720, 1280, Language.ENGLISH))
        assertEquals("Pay", result.elements.single().label)
        assertTrue(received.single().second.contains("\"media_type\":\"image/jpeg\""))
    }

    @Test
    fun `openai compatible provider uses json schema response format`() = runBlocking {
        var sent = ""
        val engine = MockEngine { request ->
            sent = String((request.body as io.ktor.http.content.TextContent).bytes())
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{\"intent\":\"FILL\",\"targetId\":\"phone\",\"value\":\"987\",\"extraFills\":[],\"reply\":\"\",\"confidence\":1}"}}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiProvider(HttpClient(engine), "k", "test-model", "https://example.test/v1")
        val result = provider.interpret(command)
        assertEquals("987", result.value)
        assertTrue(sent.contains("\"json_schema\""))
        assertTrue(sent.contains("\"strict\":true"))
    }
}
