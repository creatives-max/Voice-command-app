package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.AnswerMemory
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerMemoryTest {
    private class MapMemory : AnswerMemory {
        val values = mutableMapOf<Pair<String, String>, String>()
        override suspend fun recall(appPackage: String, key: String) = values[appPackage to key]
        override suspend fun remember(appPackage: String, key: String, label: String, value: String) {
            values[appPackage to key] = value
        }
    }

    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Full name", FieldType.NAME)
    private val city = ScreenElement("vid:city", ElementKind.TEXT_FIELD, "City")
    private val pin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "UPI PIN", FieldType.PIN, isSensitive = true)
    private val form = ScreenSnapshot("com.shop", elements = listOf(name, city, pin), signature = "form")

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, memory: AnswerMemory, remember: Boolean = true) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null }, recorder = { },
        config = { SessionConfig(confirmValues = false, rememberAnswers = remember) }, scope = this, screenSettleMillis = 10, answers = memory,
    )

    @Test
    fun `answers are offered next time and reused with yes or same as last time`() = runTest {
        val memory = MapMemory()
        engine(FakeScreen(form), ScriptedStt("Rahul Sharma", "Pune", "skip"), RecordingTts(), memory).start()
        advanceUntilIdle()
        assertEquals(mapOf(("com.shop" to "NAME:full_name") to "Rahul Sharma", ("com.shop" to "TEXT:city") to "Pune"), memory.values)

        val screen = FakeScreen(form)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("haan", "pichhli baar wala", "skip"), tts, memory).start()
        advanceUntilIdle()
        assertEquals("Rahul Sharma", screen.valueOf("vid:name"))
        assertEquals("Pune", screen.valueOf("vid:city"))
        assertTrue(tts.spoken.any { it.endsWith("Last time you said Rahul Sharma. Say yes to use it again.") })
        // The PIN is never remembered or offered.
        assertTrue(memory.values.keys.none { it.second.startsWith("PIN") })
    }

    @Test
    fun `nothing is remembered or offered when the setting is off`() = runTest {
        val memory = MapMemory().apply { values["com.shop" to "TEXT:city"] = "Delhi" }
        val tts = RecordingTts()
        engine(FakeScreen(form), ScriptedStt("Rahul", "Pune", "skip"), tts, memory, remember = false).start()
        advanceUntilIdle()
        assertEquals(mapOf(("com.shop" to "TEXT:city") to "Delhi"), memory.values)
        assertTrue(tts.spoken.none { "Last time" in it })
    }

    @Test
    fun `last time references in other languages`() {
        assertTrue(ContextResolver.isLastTimeReference("Same as last time"))
        assertTrue(ContextResolver.isLastTimeReference("पिछली बार वाला"))
        assertTrue(ContextResolver.isLastTimeReference("मागच्या वेळेसारखे"))
        assertTrue(!ContextResolver.isLastTimeReference("same as above"))
    }
}
