package com.voicecontrol.feature.inspector

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class ElementRowTest {
    @Test
    fun `sensitive values are always masked`() {
        val row = ElementRow.from(ScreenElement("id", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, value = "leak", isSensitive = true))
        assertEquals(ElementRow.MASK, row.valueLabel)
        assertEquals("text field · password", row.kindLabel)
    }

    @Test
    fun `buttons can be hidden`() {
        val snapshot = ScreenSnapshot(
            "pkg",
            elements = listOf(
                ScreenElement("a", ElementKind.TEXT_FIELD, "Name", FieldType.NAME),
                ScreenElement("b", ElementKind.BUTTON, "Submit"),
            ),
        )
        assertEquals(2, InspectorState(snapshot = snapshot).rows.size)
        assertEquals(listOf("a"), InspectorState(snapshot = snapshot, showButtons = false).rows.map { it.id })
    }
}
