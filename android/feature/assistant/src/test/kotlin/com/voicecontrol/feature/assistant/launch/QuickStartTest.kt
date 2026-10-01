package com.voicecontrol.feature.assistant.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickStartTest {
    @Test
    fun startsSessionsOnlyWhenReady() {
        assertEquals(QuickStartAction.TOGGLE_SESSION, QuickStart.decide(serviceConnected = true, micGranted = true))
        assertEquals(QuickStartAction.OPEN_APP, QuickStart.decide(serviceConnected = false, micGranted = true))
        assertEquals(QuickStartAction.OPEN_APP, QuickStart.decide(serviceConnected = true, micGranted = false))
    }
}
