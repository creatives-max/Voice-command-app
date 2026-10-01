package com.voicecontrol.application.nlp

import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.application.nlp.FieldValidator.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldValidatorTest {
    @Test fun `implicit type rules`() {
        assertEquals(Result.Valid, FieldValidator.validate("a@b.co", FieldType.EMAIL, emptyList()))
        assertTrue(FieldValidator.validate("ab.co", FieldType.EMAIL, emptyList()) is Result.Invalid)
        assertTrue(FieldValidator.validate("12345", FieldType.PINCODE, emptyList()) is Result.Invalid)
        assertEquals(Result.Valid, FieldValidator.validate("9876543210", FieldType.PHONE, emptyList()))
    }

    @Test fun `explicit dashboard rules`() {
        assertTrue(FieldValidator.validate("", FieldType.TEXT, listOf("required")) is Result.Invalid)
        assertEquals(Result.Valid, FieldValidator.validate("", FieldType.TEXT, emptyList()))
        assertEquals(Result.Valid, FieldValidator.validate("1234", null, listOf("digits:4")))
        assertTrue(FieldValidator.validate("123", null, listOf("digits:4")) is Result.Invalid)
        assertTrue(FieldValidator.validate("ab", null, listOf("min:3")) is Result.Invalid)
        assertEquals(Result.Valid, FieldValidator.validate("ABC123", null, listOf("regex:[A-Z]{3}\\d{3}")))
        assertEquals(Result.Valid, FieldValidator.validate("Male", null, listOf("oneOf:male|female|other")))
    }
}
