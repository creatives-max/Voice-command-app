package com.voicecontrol.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppRequestTest {
    @Test
    fun `finds the app name in many ways of asking`() {
        assertEquals("whatsapp", AppRequest.parse("Open WhatsApp"))
        assertEquals("youtube", AppRequest.parse("launch the YouTube app"))
        assertEquals("phonepe", AppRequest.parse("PhonePe kholo"))
        assertEquals("phonepe", AppRequest.parse("phonepe app khol do please"))
        assertEquals("google pay", AppRequest.parse("Google Pay open karo"))
        assertEquals("यूट्यूब", AppRequest.parse("यूट्यूब खोलो"))
        assertEquals("व्हाट्सएप", AppRequest.parse("ज़रा व्हाट्सएप ऐप खोल दो"))
        assertEquals("gmail", AppRequest.parse("Gmail ખોલો"))
        assertEquals("camera", AppRequest.parse("camera திற"))
    }

    @Test
    fun `other speech is not an app request`() {
        assertNull(AppRequest.parse("Rahul Sharma"))
        assertNull(AppRequest.parse("open"))
        assertNull(AppRequest.parse("kholo"))
        assertNull(AppRequest.parse("next"))
        assertNull(AppRequest.parse(""))
    }

    @Test
    fun `asking what to do is help`() {
        assertEquals(VoiceCommand.Help, CommandParser.parse("What can I do here?"))
        assertEquals(VoiceCommand.Help, CommandParser.parse("pata nahi"))
        assertEquals(VoiceCommand.Help, CommandParser.parse("क्या करूं"))
    }
}
