package com.voicecontrol.feature.assistant.scan

import com.voicecontrol.core.engine.DocField
import com.voicecontrol.core.engine.DocumentType
import com.voicecontrol.core.engine.ExtractedDocument
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScanReviewTest {
    private val form = ScreenSnapshot(
        "com.kyc",
        elements = listOf(
            ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Full name", FieldType.NAME),
            ScreenElement("vid:uid", ElementKind.TEXT_FIELD, "Aadhaar number"),
        ),
        signature = "kyc",
    )
    private val aadhaar = ExtractedDocument(
        DocumentType.AADHAAR,
        mapOf(DocField.FULL_NAME to "Priya Verma", DocField.AADHAAR to "4567 8901 2345", DocField.GENDER to "Female"),
    )

    @Test
    fun `review masks the Aadhaar number, lists unused values and fills only ticked ones`() {
        val review = assertIs<ScanStage.Review>(ScanReview.stage(form, aadhaar))
        val (name, uid) = review.items
        assertEquals("Priya Verma", name.shown)
        assertEquals("XXXX XXXX 2345", uid.shown)
        assertEquals("4567 8901 2345", uid.copy(revealed = true).shown)
        assertEquals(mapOf(DocField.GENDER to "Female"), review.unused)

        val chosen = ScanReview.chosen(listOf(name.copy(value = " Priya V. Verma "), uid.copy(checked = false)))
        assertEquals(listOf("vid:name" to "Priya V. Verma"), chosen.map { it.element.id to it.value })
        assertTrue(ScanReview.chosen(listOf(name.copy(value = "  "))).isEmpty())
    }

    @Test
    fun `explains when there is no form, nothing was read, or nothing matches`() {
        assertIs<ScanStage.Problem>(ScanReview.stage(null, aadhaar))
        assertIs<ScanStage.Problem>(ScanReview.stage(form, ExtractedDocument(DocumentType.OTHER, emptyMap())))
        val other = ScanReview.stage(form.copy(elements = listOf(ScreenElement("vid:x", ElementKind.TEXT_FIELD, "Coupon code"))), aadhaar)
        assertTrue((other as ScanStage.Problem).message.startsWith("Found name"))
    }
}
