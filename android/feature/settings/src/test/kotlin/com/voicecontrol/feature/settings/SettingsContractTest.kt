package com.voicecontrol.feature.settings

import com.voicecontrol.core.data.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsContractTest {
    @Test
    fun `local-only and vision are mutually exclusive`() {
        val vision = AppSettings().with(Option.VISION_FALLBACK, true)
        assertTrue(vision.visionFallback)
        val local = vision.with(Option.LOCAL_ONLY, true)
        assertTrue(local.localOnly)
        assertFalse(local.visionFallback)
        assertFalse(local.with(Option.VISION_FALLBACK, true).localOnly)
    }

    @Test
    fun `every option round trips`() {
        Option.entries.forEach { option ->
            assertEquals(true, AppSettings().with(option, true).isOn(option), option.name)
            assertEquals(false, AppSettings().with(option, false).isOn(option), option.name)
        }
    }

    @Test
    fun `server urls`() {
        assertTrue(isValidServerUrl(""))
        assertTrue(isValidServerUrl("https://api.voicecontrol.app"))
        assertTrue(isValidServerUrl("http://10.0.2.2:8080"))
        assertFalse(isValidServerUrl("ftp://x"))
        assertFalse(isValidServerUrl("api.example.com"))
    }
}
