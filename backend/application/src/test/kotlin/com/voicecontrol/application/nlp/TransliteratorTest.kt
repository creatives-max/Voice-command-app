package com.voicecontrol.application.nlp

import kotlin.test.Test
import kotlin.test.assertEquals

class TransliteratorTest {
    @Test fun names() {
        assertEquals("rahul sharma", Transliterator.devanagariToLatin("राहुल शर्मा"))
        assertEquals("priya", Transliterator.devanagariToLatin("प्रिया"))
        assertEquals("mumbai", Transliterator.devanagariToLatin("मुंबई"))
        assertEquals("already latin", Transliterator.devanagariToLatin("already latin"))
    }
}
