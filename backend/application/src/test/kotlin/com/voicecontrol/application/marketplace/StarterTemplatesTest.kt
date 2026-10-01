package com.voicecontrol.application.marketplace

import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.domain.flow.StepAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarterTemplatesTest {
    @Test
    fun `templates load, are valid flows and every step has keywords`() {
        val templates = StarterTemplates.load()
        assertEquals(6, templates.size)
        assertEquals(templates.size, templates.map { it.listing.id }.toSet().size)
        templates.forEach { t ->
            assertTrue(t.listing.isTemplate)
            assertTrue(t.listing.category in MarketplaceService.CATEGORIES, t.listing.name)
            // Same validation as user flows.
            FlowService.normalizeSteps(t.steps)
            t.steps.forEach { s -> assertTrue(t.listing.keywords[s.id].orEmpty().isNotEmpty(), "${t.listing.name}/${s.id}") }
            assertTrue(t.steps.none { it.defaultValue != null }, "templates never carry personal defaults")
            assertTrue(t.steps.any { it.action == StepAction.CLICK || it.action == StepAction.TOGGLE || it.action == StepAction.FILL })
        }
        val payment = templates.first { it.listing.category == "payment" }
        assertTrue(payment.steps.filter { it.label in setOf("Card number", "CVV", "Expiry") }.all { it.fieldType?.isSensitive == true })
    }
}
