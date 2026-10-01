package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared vectors: docs/spec/template-matching.json against the real starter templates. */
class TemplateMatcherTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/spec/template-matching.json").exists()) dir = dir.parentFile
        dir!!
    }

    private val templates = Json.parseToJsonElement(File(root, "backend/application/src/main/resources/templates/starter-templates.json").readText())
        .jsonObject["templates"]!!.jsonArray.map { t ->
            val o = t.jsonObject
            val steps = o["steps"]!!.jsonArray.map { it.jsonObject }
            FlowTemplate(
                id = o["id"]!!.jsonPrimitive.content,
                name = o["name"]!!.jsonPrimitive.content,
                category = o["category"]!!.jsonPrimitive.content,
                version = 1,
                steps = steps.map { json.decodeFromJsonElement(FlowStep.serializer(), it) },
                keywords = steps.associate { s: JsonObject -> s["id"]!!.jsonPrimitive.content to s["keywords"]!!.jsonArray.map { it.jsonPrimitive.content } },
            )
        }

    @Test
    fun `shared vectors pick the right template and fields`() {
        val screens = Json.parseToJsonElement(File(root, "docs/spec/template-matching.json").readText()).jsonObject["screens"]!!.jsonArray
        assertTrue(screens.size >= 4)
        screens.forEach { s ->
            val o = s.jsonObject
            val elements = o["elements"]!!.jsonArray.mapIndexed { i, e ->
                val a = e.jsonArray
                ScreenElement(
                    id = "e$i",
                    kind = ElementKind.valueOf(a[0].jsonPrimitive.content),
                    label = a[1].jsonPrimitive.content,
                    fieldType = a[2].jsonPrimitive.contentOrNull?.let(FieldType::valueOf),
                    isSensitive = a[2].jsonPrimitive.contentOrNull?.let(FieldType::valueOf)?.isSensitive == true,
                )
            }
            val name = o["name"]!!.jsonPrimitive.content
            val flow = TemplateMatcher.best(ScreenSnapshot("com.app", elements = elements, signature = "sig"), templates)
            val expected = o["expected"]!!.jsonPrimitive.contentOrNull
            if (expected == null) {
                assertNull(flow, name)
                return@forEach
            }
            assertEquals(TemplateMatcher.TEMPLATE_PREFIX + expected, flow?.id, name)
            val template = templates.first { it.id == expected }
            val mapping = o["mapping"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            val actual = TemplateMatcher.match(template, elements).mapValues { it.value.label }
            assertEquals(mapping, actual, name)
            // Adapted steps point at real elements and keep the template's questions and rules.
            assertTrue(flow!!.steps.all { st -> elements.any { it.id == st.elementId } }, name)
            assertEquals(flow.steps.map { it.order }, flow.steps.indices.toList())
        }
    }

    @Test
    fun `adapted address flow keeps template questions and validation`() {
        val elements = listOf(
            ScreenElement("a", ElementKind.TEXT_FIELD, "House / Flat", FieldType.ADDRESS),
            ScreenElement("b", ElementKind.TEXT_FIELD, "City", FieldType.TEXT),
            ScreenElement("c", ElementKind.TEXT_FIELD, "PIN code", FieldType.PINCODE),
        )
        val flow = TemplateMatcher.best(ScreenSnapshot("com.shop", elements = elements, signature = "addr"), templates)!!
        val pin = flow.steps.first { it.elementId == "c" }
        assertEquals("What is the 6 digit PIN code?", pin.question)
        assertEquals(listOf("required", "pincode"), pin.rules)
        assertEquals("com.shop", flow.appPackage)
        assertEquals("addr", flow.screenSignature)
    }

    @Test
    fun `a recorded template screen becomes a flow that keeps the template questions`() {
        val record = com.voicecontrol.core.model.ScreenRecord(
            appPackage = "com.shop", screenSignature = "addr",
            steps = listOf(
                com.voicecontrol.core.model.StepRecord("c", "City", ElementKind.TEXT_FIELD, FieldType.TEXT, outcome = com.voicecontrol.core.model.StepOutcome.FILLED),
                com.voicecontrol.core.model.StepRecord("p", "PIN code", ElementKind.TEXT_FIELD, FieldType.PINCODE, outcome = com.voicecontrol.core.model.StepOutcome.FILLED),
                com.voicecontrol.core.model.StepRecord("x", "Gift note", ElementKind.TEXT_FIELD, FieldType.TEXT, outcome = com.voicecontrol.core.model.StepOutcome.SKIPPED),
            ),
        )
        val address = templates.first { it.category == "address" }
        val flow = FlowGenerator.fromScreen(record, "local-1", 0L, address)
        assertEquals("Which city?", flow.steps.first { it.elementId == "c" }.question)
        assertEquals(listOf("required", "pincode"), flow.steps.first { it.elementId == "p" }.rules)
        assertNull(flow.steps.first { it.elementId == "x" }.question)
    }
}
