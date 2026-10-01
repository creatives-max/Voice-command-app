package com.voicecontrol.feature.onboarding

import com.voicecontrol.core.accessibility.AccessibilityBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeFormTest {
    @Test
    fun countsFilledFields() {
        assertEquals(2, PracticeForm.filledCount(listOf("Asha", " ", null, "Pune")))
    }

    @Test
    fun accessibilityBridgeKnowsThePracticeForm() {
        assertEquals(PracticeFormActivity::class.java.name, AccessibilityBridge.PRACTICE_FORM_ACTIVITY)
    }

    @Test
    fun tutorialEndsWithPractice() {
        assertEquals(OnboardingPage.PRACTICE, OnboardingPage.entries.last())
    }
}
