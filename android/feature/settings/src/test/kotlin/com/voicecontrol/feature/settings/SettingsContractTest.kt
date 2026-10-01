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
    fun `on-device speech follows the setting and on-device only mode`() {
        assertFalse(AppSettings().toSessionConfig().preferOffline)
        assertTrue(AppSettings().with(Option.OFFLINE_SPEECH, true).toSessionConfig().preferOffline)
        assertTrue(AppSettings().with(Option.LOCAL_ONLY, true).toSessionConfig().preferOffline)
        assertTrue(PackText.download(com.voicecontrol.core.engine.port.PackDownload.STARTED, com.voicecontrol.core.model.Language.TAMIL, "speech").contains("தமிழ்"))
        assertEquals("Listening works offline", PackText.speech(com.voicecontrol.core.engine.SpeechPackState.INSTALLED))
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
