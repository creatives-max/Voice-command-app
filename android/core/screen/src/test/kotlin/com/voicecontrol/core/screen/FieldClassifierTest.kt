package com.voicecontrol.core.screen

import com.voicecontrol.core.model.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldClassifierTest {
    private fun classify(label: String, inputType: Int = InputTypeBits.TYPE_CLASS_TEXT, id: String? = null, password: Boolean = false) =
        FieldClassifier.classify(editText(id = id, inputType = inputType, password = password), label)

    @Test fun `password variations are sensitive`() {
        assertEquals(FieldType.PASSWORD, classify("Secret", InputTypeBits.TYPE_CLASS_TEXT or InputTypeBits.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(FieldType.PASSWORD, classify("anything", password = true))
        assertEquals(FieldType.PIN, classify("Enter", InputTypeBits.TYPE_CLASS_NUMBER or InputTypeBits.TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals(FieldType.PIN, classify("CVV"))
        assertEquals(FieldType.OTP, classify("Enter the verification code"))
        assertEquals(FieldType.OTP, classify("ओटीपी डालें"))
    }

    @Test fun `pincode is not mistaken for pin`() {
        assertEquals(FieldType.PINCODE, classify("PIN code"))
        assertEquals(FieldType.PINCODE, classify("Zip"))
    }

    @Test fun `keyword and view id heuristics`() {
        assertEquals(FieldType.EMAIL, classify("", id = "app:id/user_email"))
        assertEquals(FieldType.PHONE, classify("WhatsApp number"))
        assertEquals(FieldType.DATE, classify("Date of birth"))
        assertEquals(FieldType.ADDRESS, classify("Delivery address"))
        assertEquals(FieldType.NAME, classify("Your name"))
        assertEquals(FieldType.TEXT, classify("Username"))
        assertEquals(FieldType.NUMBER, classify("Quantity", InputTypeBits.TYPE_CLASS_NUMBER))
        assertEquals(FieldType.AMOUNT, classify("Amount", InputTypeBits.TYPE_CLASS_NUMBER))
    }
}
