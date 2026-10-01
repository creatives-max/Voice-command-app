package com.voicecontrol.application.flow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs the shared vectors in docs/spec/expressions.json (also used by backend + dashboard). */
class ExpressionsTest {
    private val spec = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/spec/expressions.json").exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(dir!!, "docs/spec/expressions.json").readText()).jsonObject
    }
    private val vars = spec["vars"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val today = { LocalDate.of(2026, 10, 1) }

    @Test
    fun `shared vectors evaluate identically`() {
        val cases = spec["cases"]!!.jsonArray
        assertTrue(cases.size > 30)
        cases.forEach { c ->
            val o = c.jsonObject
            val expr = o["expr"]!!.jsonPrimitive.content
            val expected = o["expected"]!!.jsonPrimitive
            val actual = Expressions.evaluate(expr, { vars[it] }, today)
            when {
                expected.isString -> assertEquals(expected.content, Expressions.text(actual), expr)
                expected.booleanOrNull != null -> assertEquals(expected.booleanOrNull, actual as? Boolean, expr)
                expected.doubleOrNull != null -> assertEquals(expected.doubleOrNull!!, Expressions.number(actual)!!, 1e-9, expr)
                else -> assertEquals(null, actual, expr)
            }
        }
    }

    @Test
    fun `shared error vectors are rejected`() {
        spec["errors"]!!.jsonArray.forEach { e ->
            val expr = e.jsonObject["expr"]!!.jsonPrimitive.content
            assertNotNull(Expressions.validate(expr), expr)
        }
    }

    @Test
    fun `templates and variable discovery`() {
        assertEquals("Hello Rahul, you have 2 kids", Expressions.template("Hello {first}, you have {kids} kids", { vars[it] }, today))
        assertEquals(setOf("age", "kids"), Expressions.variables("age > 18 && kids == 2"))
        assertEquals("unchanged {bad(}", Expressions.template("unchanged {bad(}", { null }, today))
        val unused = JsonPrimitive("x").contentOrNull
        assertEquals("x", unused)
    }
}
