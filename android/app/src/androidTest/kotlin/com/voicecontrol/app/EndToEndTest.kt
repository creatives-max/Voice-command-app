package com.voicecontrol.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import android.os.SystemClock
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicecontrol.feature.onboarding.PracticeFormActivity
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import com.voicecontrol.feature.onboarding.R as OnboardingR

/**
 * Runs the real app on an emulator: first-run tutorial, home, settings (app lock and data controls),
 * on-phone flows screen and the practice form that the accessibility service fills.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_tutorialLeadsToHomeAndSettings() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Fill any form by voice")).fetchSemanticsNodes().isNotEmpty() }
        repeat(5) {
            compose.onNodeWithTag("onboarding-next").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Try it").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-finish").performClick()

        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Operate any app by voice")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tutorial and practice").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Security and your data").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Export my data").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On-device only").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Open flows").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Starter templates")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun b_practiceFormCanBeFilledAndSubmitted() {
        // A system dialog (e.g. "System UI isn't responding" on a cold emulator) can hold window focus.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
        ActivityScenario.launch(PracticeFormActivity::class.java).use {
            // replaceText never opens the soft keyboard, so no tap can land on it while it animates away;
            // it is idempotent, so the first action can be retried until the form's window has focus.
            eventually(timeoutMillis = 20_000) {
                onView(withId(OnboardingR.id.practice_name)).perform(scrollTo(), replaceText("Asha Rao"))
            }
            onView(withId(OnboardingR.id.practice_city)).perform(scrollTo(), replaceText("Pune"))
            Espresso.closeSoftKeyboard()
            onView(withId(OnboardingR.id.practice_submit)).perform(scrollTo()).check(matches(isDisplayed())).perform(click())
            eventually {
                onView(withText("You filled 2 of 4 fields. VoiceControl works the same way in every app.")).inRoot(isDialog()).check(matches(isDisplayed()))
            }
        }
    }

    /** Retries [check] for a few seconds: windows such as dialogs appear asynchronously on slow emulators. */
    private fun eventually(timeoutMillis: Long = 5_000, check: () -> Unit) {
        val end = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            try {
                check()
                return
            } catch (e: Throwable) {
                if (SystemClock.uptimeMillis() > end) throw e
                Thread.sleep(250)
            }
        }
    }
}
