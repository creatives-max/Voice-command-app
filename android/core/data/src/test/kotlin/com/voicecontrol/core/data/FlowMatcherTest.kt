package com.voicecontrol.core.data

import com.voicecontrol.core.data.flows.FlowMatcher
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowMatcherTest {
    private fun flow(id: String, sig: String) = FlowDefinition(id, 1, "pkg", id, sig, emptyList())

    @Test
    fun `exact signature wins, near matches pass threshold, unrelated screens do not match`() {
        val signup = flow("signup", "pkg|Signup|button:create account|text_field/email:email|text_field/name:full name|text_field/phone:mobile number")
        val login = flow("login", "pkg|Login|button:login|text_field/password:password|text_field/phone:mobile number")
        val live = ScreenSnapshot("pkg", elements = emptyList(),
            signature = "pkg|Signup|button:create account|text_field/email:email|text_field/name:full name|text_field/phone:mobile no")
        assertEquals("signup", FlowMatcher.best(live, listOf(login, signup))?.id)
        assertEquals("login", FlowMatcher.best(live.copy(signature = login.screenSignature), listOf(signup, login))?.id)
        assertNull(FlowMatcher.best(live.copy(signature = "pkg|X|button:pay"), listOf(signup, login)))
    }
}
