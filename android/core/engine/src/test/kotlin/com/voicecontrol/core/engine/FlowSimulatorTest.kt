package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.FlowSimulator.EntryKind
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.UserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the shared vectors in docs/spec/simulation.json (also run by the dashboard and the backend). */
class FlowSimulatorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val spec = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/spec/simulation.json").exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(dir!!, "docs/spec/simulation.json").readText()).jsonObject
    }
    private val today = { LocalDate.of(2026, 10, 1) }

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
            val profile = o["profile"]?.let { json.decodeFromJsonElement<UserProfile>(it) } ?: UserProfile()
            val r = FlowSimulator.simulate(steps, o.strings("answers")!!, FlowSimulator.profileVariables(profile::value), today)
            val e = o["expected"]!!.jsonObject
            fun of(kind: EntryKind) = r.transcript.filter { it.kind == kind }

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
            e.strings("asks")?.let { assertEquals(it, of(EntryKind.ASK).map { x -> x.text }, name) }
            e.strings("presses")?.let { assertEquals(it, of(EntryKind.PRESS).map { x -> x.text }, name) }
            e.strings("screens")?.let { assertEquals(it, of(EntryKind.SCREEN).map { x -> x.text }, name) }
            e["errorCount"]?.jsonPrimitive?.intOrNull?.let { assertEquals(it, of(EntryKind.ERROR).size, name) }
            e["manualCount"]?.jsonPrimitive?.intOrNull?.let { assertEquals(it, of(EntryKind.MANUAL).size, name) }
            assertTrue(r.finished == (of(EntryKind.DONE).size == 1), name)
        }
    }

    @Test
    fun `answering the pending question continues the run`() {
        val steps = spec["cases"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content.startsWith("stops at") }
            .jsonObject["steps"]!!.jsonArray.map { json.decodeFromJsonElement<FlowStep>(it) }
        val first = FlowSimulator.simulate(steps, listOf("yes"), today = today)
        val second = FlowSimulator.simulate(steps, listOf("yes", "Priya"), today = today)
        assertEquals("s", first.pending?.stepId)
        assertTrue(second.finished)
        assertEquals("Priya", second.values["s"])
    }
}
