package com.voicecontrol.core.nlp

import com.voicecontrol.core.model.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechNormalizerTest {
    @Test fun `email dictation`() {
        assertEquals("rahul.sharma@gmail.com", SpeechNormalizer.normalize("Rahul dot Sharma at the rate gmail dot com", FieldType.EMAIL))
        assertEquals("rahul_99@yahoo.in", SpeechNormalizer.normalize("my email is rahul underscore 99 at the rate yahoo dot in", FieldType.EMAIL))
        assertEquals("rahul@gmail.com", SpeechNormalizer.normalize("राहुल एट द रेट जीमेल डॉट कॉम", FieldType.EMAIL))
    }

    @Test fun `phone with country code`() {
        assertEquals("9876543210", SpeechNormalizer.normalize("plus nine one nine eight seven six five four three two one zero", FieldType.PHONE))
        assertEquals("9876543210", SpeechNormalizer.normalize("+91 98765 43210", FieldType.PHONE))
        assertEquals("9876543210", SpeechNormalizer.normalize("मेरा नंबर नौ आठ सात छह पांच चार तीन दो एक शून्य है", FieldType.PHONE))
    }

    @Test fun `names strip lead-ins and title case`() {
        assertEquals("Rahul Sharma", SpeechNormalizer.normalize("my name is rahul sharma", FieldType.NAME))
        assertEquals("Rahul", SpeechNormalizer.normalize("mera naam rahul hai", FieldType.NAME))
        assertEquals("राहुल", SpeechNormalizer.normalize("मेरा नाम राहुल है", FieldType.NAME))
        assertEquals("Rahul Sharma", SpeechNormalizer.normalize("मेरा नाम राहुल शर्मा है", FieldType.NAME, transliterate = true))
    }

    @Test fun `pincode amount and date`() {
        assertEquals("110001", SpeechNormalizer.normalize("one one zero zero zero one", FieldType.PINCODE))
        assertEquals("1500", SpeechNormalizer.normalize("fifteen hundred", FieldType.AMOUNT))
        assertEquals("12/03/1990", SpeechNormalizer.normalize("12th March 1990", FieldType.DATE))
        assertEquals("05/08/2001", SpeechNormalizer.normalize("5/8/01", FieldType.DATE))
    }

    @Test fun `plain text keeps content`() {
        assertEquals("Flat 4B, MG Road", SpeechNormalizer.normalize("Flat 4B,  MG Road", FieldType.ADDRESS))
    }
}
