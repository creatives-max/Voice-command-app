package com.voicecontrol.feature.auth

import com.voicecontrol.core.model.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileContractTest {
    @Test
    fun `edits map to fields and blanks clear them`() {
        val p = UserProfile().with(ProfileField.CITY, "Pune").with(ProfileField.PINCODE, "411001")
        assertEquals("Pune", p.valueOf(ProfileField.CITY))
        assertNull(p.with(ProfileField.CITY, " ").city)
    }

    @Test
    fun `validation mirrors server rules`() {
        assertEquals("PIN code must be 6 digits", UserProfile(pincode = "12").validationError())
        assertEquals("Phone must have 10 to 13 digits", UserProfile(phone = "123").validationError())
        assertNull(UserProfile(phone = "+91 98765 43210", pincode = "110001", dateOfBirth = "12/03/1990").validationError())
    }

    @Test
    fun `auth form enables submit only when plausible`() {
        assertEquals(false, AuthState(email = "x", password = "y").canSubmit)
        assertEquals(true, AuthState(email = "a@b.co", password = "y").canSubmit)
        assertEquals(false, AuthState(registerMode = true, email = "a@b.co", password = "short").canSubmit)
    }
}
