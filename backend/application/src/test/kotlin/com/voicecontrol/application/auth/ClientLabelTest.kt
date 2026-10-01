package com.voicecontrol.application.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientLabelTest {
    @Test
    fun `describes browsers, the app and unknown clients`() {
        assertEquals(
            "Chrome on Windows",
            ClientLabel.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"),
        )
        assertEquals(
            "Edge on Windows",
            ClientLabel.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36 Edg/129.0"),
        )
        assertEquals("Safari on iPhone", ClientLabel.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
        assertEquals("Firefox on macOS", ClientLabel.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:130.0) Gecko/20100101 Firefox/130.0"))
        assertEquals("VoiceControl app 1.4.0 on Android", ClientLabel.describe("VoiceControl-Android/1.4.0 (Pixel 8; Android 15)"))
        assertEquals("VoiceControl app", ClientLabel.describe("Ktor client"))
        assertEquals("curl/8.5.0", ClientLabel.describe("curl/8.5.0"))
        assertNull(ClientLabel.describe("  "))
        assertNull(ClientLabel.describe(null))
    }
}
