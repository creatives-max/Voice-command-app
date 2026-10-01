package com.voicecontrol.application.nlp

import com.voicecontrol.domain.ai.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Same cases as the phone (android/core/nlp IndianLanguagesTest), for the backend rules provider. */
class IndianLanguagesTest {

    @Test
    fun `commands in each new language`() {
        val cases = mapOf(
            "पुढे" to VoiceCommand.Next, "थांबा" to VoiceCommand.Stop, "होय" to VoiceCommand.Yes, "नाही" to VoiceCommand.No,
            "அடுத்து" to VoiceCommand.Next, "நிறுத்து" to VoiceCommand.Stop, "ஆம்" to VoiceCommand.Yes, "இல்லை" to VoiceCommand.No,
            "తరువాత" to VoiceCommand.Next, "ఆపు" to VoiceCommand.Stop, "అవును" to VoiceCommand.Yes, "వద్దు" to VoiceCommand.No,
            "পরের" to VoiceCommand.Next, "থামো" to VoiceCommand.Stop, "হ্যাঁ" to VoiceCommand.Yes, "না" to VoiceCommand.No,
            "આગળ" to VoiceCommand.Next, "રોકો" to VoiceCommand.Stop, "હા" to VoiceCommand.Yes, "ના" to VoiceCommand.No,
            "मागे" to VoiceCommand.Back, "மீண்டும்" to VoiceCommand.Repeat, "మళ్ళీ" to VoiceCommand.Repeat, "জমা দাও" to VoiceCommand.Submit,
            "છોડો" to VoiceCommand.Skip, "சமர்ப்பி" to VoiceCommand.Submit, "కిందకి" to VoiceCommand.ScrollDown, "উপরে" to VoiceCommand.ScrollUp,
            "मदत" to VoiceCommand.Help, "उघडा" to null,
        )
        cases.forEach { (speech, expected) -> assertEquals(expected, CommandParser.parse(speech), speech) }
        assertEquals(VoiceCommand.Press("login"), CommandParser.parse("Login அழுத்து"))
        assertEquals(VoiceCommand.Press("login"), CommandParser.parse("Login దాబా".replace("దాబా", "నొక్కు")))
        assertEquals(VoiceCommand.Press("submit"), CommandParser.parse("Submit চাপুন"))
        assertEquals(VoiceCommand.Press("login"), CommandParser.parse("Login દબાવો"))
        // Polite forms
        assertEquals(VoiceCommand.Stop, CommandParser.parse("बंद करा"))
        assertEquals(VoiceCommand.Next, CommandParser.parse("తరువాత చేయండి"))
    }

    @Test
    fun `native digits and digit words`() {
        assertEquals("9876543210", NumberParser.digitString("৯৮৭৬৫৪৩২১০"))
        assertEquals("411001", NumberParser.digitString("૪૧૧૦૦૧"))
        assertEquals("560001", NumberParser.digitString("௫௬௦௦௦௧"))
        assertEquals("500081", NumberParser.digitString("౫౦౦౦౮౧"))
        assertEquals("9821", NumberParser.digitString("नऊ आठ दोन एक"))
        assertEquals("123", NumberParser.digitString("ஒன்று இரண்டு மூன்று"))
        assertEquals("456", NumberParser.digitString("నాలుగు ఐదు ఆరు"))
        assertEquals("789", NumberParser.digitString("সাত আট নয়"))
        assertEquals("230", NumberParser.digitString("બે ત્રણ શૂન્ય"))
        assertEquals(2_000.0, NumberParser.value("இரண்டு ஆயிரம்"))
        assertEquals(500.0, NumberParser.value("ఐదు వంద"))
        assertEquals(300_000.0, NumberParser.value("তিন লাখ"))
        assertEquals("98", SpeechNormalizer.normalize("৯৮", FieldType.NUMBER))
    }

    @Test
    fun `names in other scripts are transliterated`() {
        assertEquals("Rahul Sharma", SpeechNormalizer.normalize("রাহুল শর্মা", FieldType.NAME, transliterate = true))
        assertEquals("Rahul", SpeechNormalizer.normalize("રાહુલ", FieldType.NAME, transliterate = true))
        assertEquals("Priya", SpeechNormalizer.normalize("ప్రియ", FieldType.NAME, transliterate = true))
        assertEquals("Kumar", SpeechNormalizer.normalize("குமார்", FieldType.NAME, transliterate = true))
        assertEquals("Pune", SpeechNormalizer.normalize("पुणे", FieldType.NAME, transliterate = true))
        assertTrue(Transliterator.containsIndic("নমস্কার"))
        assertFalse(Transliterator.containsIndic("hello"))
        assertEquals("রাহুল", SpeechNormalizer.normalize("রাহুল", FieldType.NAME, transliterate = false))
    }
}
