package com.voicecontrol.core.voice

import android.speech.SpeechRecognizer
import com.voicecontrol.core.engine.port.ListenResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMappingTest {
    @Test
    fun `silence is no match and permission errors are fatal`() {
        assertEquals(ListenResult.NoMatch, AndroidSpeechToText.mapError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(ListenResult.NoMatch, AndroidSpeechToText.mapError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        val fatal = AndroidSpeechToText.mapError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(fatal is ListenResult.Error && !fatal.recoverable)
        val network = AndroidSpeechToText.mapError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(network is ListenResult.Error && network.recoverable)
    }
}
