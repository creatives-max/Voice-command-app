package com.voicecontrol.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class TextMaskTest {
    @Test
    fun `long numbers keep their last four digits, codes near secret words are hidden, amounts stay`() {
        assertEquals("A/c •••••••9012", TextMask.mask("A/c 12345679012"))
        assertEquals("Card ••••••••••••3456", TextMask.mask("Card 1234 5678 9012 3456"))
        assertEquals("Mobile ••••••3210", TextMask.mask("Mobile 98765-43210"))
        assertEquals("Your OTP is ••••", TextMask.mask("Your OTP is 482913"))
        assertEquals("Bill amount ₹540, due 12/10/2026", TextMask.mask("Bill amount ₹540, due 12/10/2026"))
        assertEquals("Consumer no. 1234567", TextMask.mask("Consumer no. 1234567"))
    }
}
