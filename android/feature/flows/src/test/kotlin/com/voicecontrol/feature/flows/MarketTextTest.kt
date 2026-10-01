package com.voicecontrol.feature.flows

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.network.dto.ListingDetailDto
import com.voicecontrol.core.network.dto.ListingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketTextTest {
    private val listing = ListingDto("l1", "IRCTC booking", category = "travel", latestVersion = 3, appPackage = "cris.org.in.prs.ima")

    @Test
    fun `describes listings, steps and install state`() {
        assertEquals("No ratings yet", MarketText.rating(null, 0))
        assertEquals("★ 4.5 (12)", MarketText.rating(4.5, 12))
        assertEquals("New", MarketText.installs(0))
        assertEquals("1 install", MarketText.installs(1))
        assertEquals("Personal finance", MarketText.category("personal_finance"))
        assertEquals("Asks for Passenger name", MarketText.step(FlowStep("s", 0, "v", "Passenger name", ElementKind.TEXT_FIELD)))
        assertEquals("Presses Book", MarketText.step(FlowStep("b", 1, "v2", "Book", ElementKind.BUTTON, action = StepAction.CLICK)))
        assertEquals("Opens com.pay", MarketText.step(FlowStep("o", 2, "", "Open", ElementKind.BUTTON, action = StepAction.OPEN_APP, appPackage = "com.pay")))

        assertEquals("Install", MarketText.installLabel(ListingDetailDto(listing)))
        assertEquals("Installed (version 3)", MarketText.installLabel(ListingDetailDto(listing, importedFlowId = "f", importedVersion = 3)))
        assertEquals("Update to version 3", MarketText.installLabel(ListingDetailDto(listing, importedFlowId = "f", importedVersion = 2, updateAvailable = true)))
        assertEquals("Spam or advertising", MarketText.reported("SPAM"))
        assertNull(MarketText.reported(null))
    }
}
