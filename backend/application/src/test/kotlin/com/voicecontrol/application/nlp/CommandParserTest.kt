package com.voicecontrol.application.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandParserTest {
    @Test fun `english commands`() {
        assertEquals(VoiceCommand.Next, CommandParser.parse("Next"))
        assertEquals(VoiceCommand.Submit, CommandParser.parse("submit please"))
        assertEquals(VoiceCommand.Back, CommandParser.parse("go back"))
        assertEquals(VoiceCommand.ScrollDown, CommandParser.parse("scroll down"))
        assertEquals(VoiceCommand.ScrollUp, CommandParser.parse("scroll up"))
        assertEquals(VoiceCommand.Yes, CommandParser.parse("okay"))
    }

    @Test fun `hindi and hinglish commands`() {
        assertEquals(VoiceCommand.Next, CommandParser.parse("आगे बढ़ो"))
        assertEquals(VoiceCommand.Submit, CommandParser.parse("जमा करो"))
        assertEquals(VoiceCommand.Back, CommandParser.parse("wapas jao"))
        assertEquals(VoiceCommand.ScrollDown, CommandParser.parse("neeche"))
        assertEquals(VoiceCommand.Stop, CommandParser.parse("band karo"))
        assertEquals(VoiceCommand.Yes, CommandParser.parse("haan ji"))
        assertEquals(VoiceCommand.No, CommandParser.parse("नहीं"))
        assertEquals(VoiceCommand.Skip, CommandParser.parse("rehne do"))
        assertEquals(VoiceCommand.Repeat, CommandParser.parse("फिर से"))
    }

    @Test fun `press commands extract the target`() {
        assertEquals(VoiceCommand.Press("login"), CommandParser.parse("press login"))
        assertEquals(VoiceCommand.Press("submit"), CommandParser.parse("click on submit"))
        assertEquals(VoiceCommand.Press("login"), CommandParser.parse("login button pe click karo"))
        assertEquals(VoiceCommand.Press("pay now"), CommandParser.parse("pay now dabao"))
        assertEquals(VoiceCommand.Press("लॉगिन"), CommandParser.parse("लॉगिन बटन दबाओ"))
    }

    @Test fun `answers containing command words are not commands`() {
        assertNull(CommandParser.parse("Next Generation School"))
        assertNull(CommandParser.parse("back office road"))
        assertNull(CommandParser.parse("rahul"))
    }
}
