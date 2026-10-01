package com.voicecontrol.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabelTextTest {
    @Test fun clean() {
        assertEquals("Email", LabelText.clean("  Email * : "))
        assertNull(LabelText.clean("  * "))
    }

    @Test fun humanize() {
        assertEquals("First name", LabelText.humanizeViewId("com.app:id/et_first_name"))
        assertEquals("Mobile number", LabelText.humanizeViewId("com.app:id/mobileNumberInput"))
        assertNull(LabelText.humanizeViewId("com.app:id/et"))
    }

    @Test fun `hash is deterministic`() {
        assertEquals(LabelText.shortHash("email"), LabelText.shortHash("email"))
        assertEquals(8, LabelText.shortHash("x").length)
    }
}
