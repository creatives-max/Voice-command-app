package com.voicecontrol.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberParserTest {
    @Test fun `english digits with double and triple`() {
        assertEquals("9877700012", NumberParser.digitString("nine eight double seven seven triple zero one two"))
    }

    @Test fun `hindi devanagari and hinglish digits`() {
        assertEquals("987", NumberParser.digitString("नौ आठ सात"))
        assertEquals("1234", NumberParser.digitString("ek do teen char"))
        assertEquals("98", NumberParser.digitString("९८"))
    }

    @Test fun `mixed numerals and words with fillers`() {
        assertEquals("9876543210", NumberParser.digitString("my number is 98765 43210"))
        assertEquals("98", NumberParser.digitString("ninety eight"))
        assertEquals("+919876", NumberParser.digitString("+91 9876"))
    }

    @Test fun `no digits returns null`() {
        assertNull(NumberParser.digitString("hello there"))
    }

    @Test fun `values in english and hindi`() {
        assertEquals(2500.0, NumberParser.value("two thousand five hundred"))
        assertEquals(500.0, NumberParser.value("paanch sau"))
        assertEquals(2500.0, NumberParser.value("ढाई हज़ार"))
        assertEquals(150000.0, NumberParser.value("dedh lakh"))
        assertEquals(42.0, NumberParser.value("42"))
        assertEquals(25.0, NumberParser.value("twenty five"))
        assertEquals("2500", NumberParser.format(2500.0))
    }
}
