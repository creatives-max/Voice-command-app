package com.voicecontrol.infrastructure.ai

import com.voicecontrol.application.match.SignatureText
import com.voicecontrol.infrastructure.embedding.HashingEmbeddingProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HashingEmbeddingTest {
    private val provider = HashingEmbeddingProvider()
    private fun cos(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    private val signup = "com.shop|SignupActivity|button:create account|text_field/email:email address|text_field/name:full name|text_field/phone:mobile number"
    private val signupRenamed = "com.shop|SignupActivity|button:create account|text_field/email:email|text_field/name:full name|text_field/phone:mobile no"
    private val payment = "com.shop|PaymentActivity|button:pay now|text_field/amount:amount|text_field/number:card number|text_field/pin:cvv"

    @Test
    fun `vectors are unit length and deterministic`() {
        val v = provider.embedSync(SignatureText.of(signup))
        assertEquals(256, v.size)
        assertEquals(1.0, cos(v, v), 1e-4)
        assertTrue(v.contentEquals(provider.embedSync(SignatureText.of(signup))))
    }

    @Test
    fun `slightly renamed labels stay above the match threshold, other screens fall below`() {
        val a = provider.embedSync(SignatureText.of(signup))
        val b = provider.embedSync(SignatureText.of(signupRenamed))
        val c = provider.embedSync(SignatureText.of(payment))
        assertTrue(cos(a, b) >= 0.80, "renamed similarity ${cos(a, b)}")
        assertTrue(cos(a, c) < 0.60, "different screen similarity ${cos(a, c)}")
    }

    @Test
    fun `signature text drops the package and humanizes parts`() {
        assertEquals("Signup. button create account. text field email email address", SignatureText.of("com.x|SignupActivity|button:create account|text_field/email:email address"))
    }
}
