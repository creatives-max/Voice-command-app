package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.IntentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelOutputTest {
    @Test
    fun `empty strings become nulls and fences are tolerated`() {
        val r = ModelOutput.interpretation("```json\n{\"intent\":\"fill\",\"targetId\":\"a\",\"value\":\"x\",\"extraFills\":[],\"reply\":\"\",\"confidence\":0.9}\n```", "m")
        assertEquals(IntentKind.FILL, r.intent)
        assertNull(r.reply)
        assertEquals(0.9f, r.confidence)
    }

    @Test
    fun `vision elements are parsed and invalid boxes dropped`() {
        val v = ModelOutput.vision("""{"elements":[{"id":"e1","kind":"TEXT_FIELD","label":"Name","fieldType":"NAME","x":1,"y":2,"width":300,"height":50},{"id":"e2","kind":"BUTTON","label":"Go","fieldType":"","x":0,"y":0,"width":0,"height":0}]}""", "m")
        assertEquals(1, v.elements.size)
        assertEquals("vision:e1", v.elements[0].id)
        assertEquals(ElementKind.TEXT_FIELD, v.elements[0].kind)
        assertEquals(FieldType.NAME, v.elements[0].fieldType)
    }
}
