package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Button names said in Hindi script match labels written in English (and the other way), by sound. */
class ScriptMatchTest {
    private fun hit(spoken: String, vararg labels: String) =
        ButtonMatcher.find(spoken, labels.mapIndexed { i, l -> ScreenElement("b$i", ElementKind.BUTTON, l) }, ButtonMatcher.STRICT)?.label

    @Test
    fun `hindi script names match english buttons by sound`() {
        listOf(
            "रिचार्ज" to "Recharge", "साइन अप" to "Sign up", "प्रोफाइल" to "Profile", "वीडियो" to "Videos", "कंटिन्यू" to "Continue",
            "सबमिट" to "Submit", "सेटिंग्स" to "Settings", "कार्ट" to "Cart", "फ्लिपकार्ट" to "Flipkart", "कॉल्स" to "Calls", "चैट्स" to "Chats",
            "recharge" to "रिचार्ज करें", "home" to "होम",
        ).forEach { (spoken, label) -> assertEquals(label, hit(spoken, "Help", label), spoken) }
    }

    @Test
    fun `sound matching never mixes up words in the same script`() {
        assertNull(hit("back", "Bike"))
        assertNull(hit("pay", "Sign up"))
        assertNull(hit("ok", "Book"))
        assertNull(hit("पे", "Sign up"))
    }
}
