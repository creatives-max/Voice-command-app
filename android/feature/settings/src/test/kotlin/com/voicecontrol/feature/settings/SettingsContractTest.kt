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
    fun `smart mode and on-device only exclude each other`() {
        val smart = AppSettings().with(Option.SMART_MODE, true)
        assertTrue(smart.toSessionConfig().smartMode)
        val local = smart.with(Option.LOCAL_ONLY, true)
        assertFalse(local.smartMode)
        assertFalse(local.toSessionConfig().smartMode)
        assertFalse(local.with(Option.SMART_MODE, true).localOnly)
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
    fun `wake phrase test results and remembered answers by app`() {
        val phrase = "hey voice control"
        assertEquals("Heard “Hey voice control”. That wakes VoiceControl.", wakeTestMessage(com.voicecontrol.core.engine.port.ListenResult.Heard("Hey voice control"), phrase))
        assertTrue(wakeTestMessage(com.voicecontrol.core.engine.port.ListenResult.Heard("hello there"), phrase).contains("doesn't match"))
        assertTrue(wakeTestMessage(com.voicecontrol.core.engine.port.ListenResult.Heard("hey boys", listOf("hey voicecontrol")), phrase).endsWith("That wakes VoiceControl."))
        assertTrue(wakeTestMessage(com.voicecontrol.core.engine.port.ListenResult.NoMatch, phrase).startsWith("Didn't hear"))

        val a = com.voicecontrol.core.data.memory.RememberedAnswer("com.a", "TEXT:city", "City", "Pune", 1)
        val b = com.voicecontrol.core.data.memory.RememberedAnswer("com.b", "NAME:name", "Name", "Asha", 5)
        val c = com.voicecontrol.core.data.memory.RememberedAnswer("com.a", "EMAIL:email", "Email", "a@b.co", 3)
        assertEquals(listOf("com.b", "com.a"), groupByApp(listOf(a, b, c)).map { it.first })
        assertTrue(AppSettings().with(Option.REMEMBER_ANSWERS, true).toSessionConfig().rememberAnswers)
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
