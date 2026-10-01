package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanBuilderTest {
    private val builder = PlanBuilder(Phrases(Language.ENGLISH))
    private val first = ScreenElement("vid:first", ElementKind.TEXT_FIELD, "First name", FieldType.NAME)
    private val mail = ScreenElement("lbl:text_field:abc", ElementKind.TEXT_FIELD, "Email address", FieldType.EMAIL)
    private val newField = ScreenElement("vid:referral", ElementKind.TEXT_FIELD, "Referral code", FieldType.TEXT)
    private val go = ScreenElement("vid:go", ElementKind.BUTTON, "Continue")

    @Test
    fun `flow steps are matched by id then label and new fields appended`() {
        val flow = FlowDefinition(
            "f", 1, "pkg", "n", "sig",
            listOf(
                FlowStep("2", 1, "vid:first", "First name", ElementKind.TEXT_FIELD),
                // id changed on screen, label still matches
                FlowStep("1", 0, "vid:old_email", "Email address", ElementKind.TEXT_FIELD, question = "Mail?"),
            ),
        )
        val plan = builder.build(ScreenSnapshot("pkg", elements = listOf(first, mail, newField, go)), flow, null)
        assertEquals(listOf("lbl:text_field:abc", "vid:first", "vid:referral"), plan.steps.map { it.elementId })
        assertEquals("Mail?", plan.steps[0].question)
        assertEquals(go, plan.submitButton)
    }

    @Test
    fun `profile keys are inferred and values suggested`() {
        val plan = builder.build(ScreenSnapshot("pkg", elements = listOf(first, mail)), null, UserProfile(fullName = "Rahul Sharma", email = "r@x.in"))
        assertEquals(ProfileKey.FIRST_NAME, plan.steps[0].profileKey)
        assertEquals("Rahul", plan.steps[0].suggestedValue)
        assertEquals("r@x.in", plan.steps[1].suggestedValue)
    }

    @Test
    fun `primary submit picks known words`() {
        val els = listOf(ScreenElement("a", ElementKind.BUTTON, "Forgot password?"), ScreenElement("b", ElementKind.BUTTON, "Login"))
        assertEquals("b", ButtonMatcher.primarySubmit(els)?.id)
        assertEquals("a", ButtonMatcher.find("forgot password", els)?.id)
    }
}

class FlowGeneratorTest {
    @Test
    fun `recorded screen becomes an ordered flow with a click step`() {
        val record = com.voicecontrol.core.model.ScreenRecord(
            appPackage = "com.shop.app", activityName = "com.shop.app.SignupActivity", screenSignature = "sig",
            steps = listOf(
                com.voicecontrol.core.model.StepRecord("vid:name", "Name", ElementKind.TEXT_FIELD, FieldType.NAME, "Please say Name.", com.voicecontrol.core.model.StepOutcome.FILLED),
                com.voicecontrol.core.model.StepRecord("vid:terms", "Terms", ElementKind.CHECKBOX, null, null, com.voicecontrol.core.model.StepOutcome.TOGGLED),
                com.voicecontrol.core.model.StepRecord("vid:go", "Sign up", ElementKind.BUTTON, null, null, com.voicecontrol.core.model.StepOutcome.CLICKED),
            ),
        )
        val flow = FlowGenerator.fromScreen(record, "local-1", 5L)
        assertEquals("App · Signup", flow.name)
        assertEquals(listOf(com.voicecontrol.core.model.StepAction.FILL, com.voicecontrol.core.model.StepAction.TOGGLE, com.voicecontrol.core.model.StepAction.CLICK), flow.orderedSteps.map { it.action })
        assertEquals("sig", flow.screenSignature)
    }
}
