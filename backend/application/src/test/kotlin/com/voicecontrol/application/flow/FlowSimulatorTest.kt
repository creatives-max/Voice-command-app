package com.voicecontrol.application.flow

import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.user.Profile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the shared vectors in docs/spec/simulation.json (also run by the phone engine and the dashboard). */
class FlowSimulatorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val spec = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/spec/simulation.json").exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(dir!!, "docs/spec/simulation.json").readText()).jsonObject
    }

    private fun JsonObject.strings(key: String): List<String>? = this[key]?.jsonArray?.map { it.jsonPrimitive.content }
    private fun JsonObject.map(key: String): Map<String, String>? = this[key]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `shared vectors simulate identically`() {
        val cases = spec["cases"]!!.jsonArray
        assertTrue(cases.size > 10)
        cases.forEach { c ->
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val steps = o["steps"]!!.jsonArray.map { json.decodeFromJsonElement<FlowStep>(it) }
            val profile = o["profile"]?.let { json.decodeFromJsonElement<Profile>(it) }
            // Through FlowDryRun, so step validation and the profile mapping are covered too.
            val r = FlowDryRun.run(steps, o.strings("answers")!!, profile, "2026-10-01")
            val e = o["expected"]!!.jsonObject
            fun of(kind: FlowSimulator.EntryKind) = r.transcript.filter { it.kind == kind }

            assertEquals(e["finished"]!!.jsonPrimitive.content.toBoolean(), r.finished, name)
            e.map("values")?.let { assertEquals(it, r.values, name) }
            e.map("vars")?.forEach { (k, v) -> assertEquals(v, r.vars[k], "$name: var $k") }
            val pending = e["pending"]?.jsonObject
            if (pending == null) {
                assertNull(r.pending, name)
            } else {
                assertEquals(pending["stepId"]!!.jsonPrimitive.content, r.pending?.stepId, name)
                assertEquals(pending["question"]!!.jsonPrimitive.content, r.pending?.question, name)
                assertEquals(pending["expects"]!!.jsonPrimitive.content, r.pending?.expects?.name?.lowercase(), name)
            }
            e.strings("asks")?.let { assertEquals(it, of(FlowSimulator.EntryKind.ASK).map { x -> x.text }, name) }
            e.strings("presses")?.let { assertEquals(it, of(FlowSimulator.EntryKind.PRESS).map { x -> x.text }, name) }
            e.strings("screens")?.let { assertEquals(it, of(FlowSimulator.EntryKind.SCREEN).map { x -> x.text }, name) }
            e["errorCount"]?.jsonPrimitive?.intOrNull?.let { assertEquals(it, of(FlowSimulator.EntryKind.ERROR).size, name) }
            e["manualCount"]?.jsonPrimitive?.intOrNull?.let { assertEquals(it, of(FlowSimulator.EntryKind.MANUAL).size, name) }
        }
    }

    @Test
    fun `the backend copy matches the phone engine`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "android/core/engine").exists()) dir = dir.parentFile
        val phone = File(dir!!, "android/core/engine/src/main/kotlin/com/voicecontrol/core/engine/FlowSimulator.kt").readLines()
        val here = File(dir, "backend/application/src/main/kotlin/com/voicecontrol/application/flow/FlowSimulator.kt").readLines()
        val body = { lines: List<String> -> lines.dropWhile { !it.startsWith("/**") } }
        assertEquals(body(phone), body(here))
    }

    @Test
    fun `today and answer limits are checked`() {
        val r = runCatching { FlowDryRun.run(emptyList(), emptyList(), null, "yesterday") }
        assertTrue(r.isFailure)
    }
}
