package com.voicecontrol.feature.care

import com.voicecontrol.core.network.dto.CareEventDto
import com.voicecontrol.core.network.dto.CareLinkDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CareTextTest {
    private fun link(role: String, status: String, id: String = role + status) = CareLinkDto(id, role, status, otherEmail = "son@example.com", createdAt = "2026-10-01T10:00:00Z")

    @Test
    fun `describes activity, reads codes aloud and counts down`() {
        val edited = CareEventDto(1, "flow_edited", "son@example.com", mapOf("flowName" to "Pay bill"), "2026-10-01T10:00:00Z")
        assertEquals("son@example.com edited “Pay bill”", CareText.describe(edited, "mom@example.com"))
        assertEquals("You edited “Pay bill”", CareText.describe(edited, "son@example.com"))
        val perms = CareEventDto(2, "permissions_changed", "mom@example.com", mapOf("permissions" to "edit_flows,run_flows"), "2026-10-01T10:00:00Z")
        assertEquals("You changed permissions to edit my flows and when they run, run flows on this phone", CareText.describe(perms, "mom@example.com"))
        assertEquals("son@example.com ran a flow on your phone", CareText.describe(CareEventDto(3, "flow_run", "son@example.com", emptyMap(), "x"), null))

        assertEquals("K 7 Q M, 2 9 P X", CareText.spoken("K7QM-29PX"))
        val now = Instant.parse("2026-10-01T10:00:00Z")
        assertEquals(30, CareText.minutesLeft("2026-10-01T10:29:10Z", now))
        assertEquals(0, CareText.minutesLeft("2026-10-01T09:00:00Z", now))
        assertEquals(0, CareText.minutesLeft("not a date", now))
    }

    @Test
    fun `splits links into helpers, open invites and people helped`() {
        val (helpers, pending, helping) = CareText.split(
            listOf(link("receiver", "ACTIVE"), link("receiver", "PENDING"), link("caregiver", "ACTIVE"), link("receiver", "REVOKED")),
        )
        assertEquals(listOf("receiverACTIVE"), helpers.map { it.id })
        assertEquals(listOf("receiverPENDING"), pending.map { it.id })
        assertEquals(listOf("caregiverACTIVE"), helping.map { it.id })
        assertEquals("son@example.com", CareText.name(helpers.single()))
    }
}
