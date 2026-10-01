package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentFillTest {
    private fun ocr(text: String) = OcrText(text.trimIndent().lines())

    private val panCard = ocr(
        """
        आयकर विभाग INCOME TAX DEPARTMENT
        भारत सरकार GOVT. OF INDIA
        स्थायी लेखा संख्या कार्ड
        Permanent Account Number Card
        ABCPS1234K
        नाम / Name
        RAHUL SHARMA
        पिता का नाम / Father's Name
        SURESH SHARMA
        जन्म की तारीख / Date of Birth
        15/08/1990
        """,
    )

    @Test
    fun `reads a PAN card`() {
        val doc = DocumentExtractor.extract(panCard)
        assertEquals(DocumentType.PAN_CARD, doc.type)
        assertEquals("ABCPS1234K", doc.fields[DocField.PAN])
        assertEquals("Rahul Sharma", doc.fields[DocField.FULL_NAME])
        assertEquals("Suresh Sharma", doc.fields[DocField.FATHER_NAME])
        assertEquals("15/08/1990", doc.fields[DocField.DATE_OF_BIRTH])
        assertNull(doc.fields[DocField.ACCOUNT_NUMBER])
    }

    @Test
    fun `reads both sides of an Aadhaar card`() {
        val front = DocumentExtractor.extract(
            ocr(
                """
                भारत सरकार
                GOVERNMENT OF INDIA
                प्रिया वर्मा
                Priya Verma
                जन्म तिथि/DOB: 2/11/1988
                महिला / FEMALE
                4567 8901 2345
                VID : 9123 4567 8901 2345
                मेरा आधार, मेरी पहचान
                """,
            ),
        )
        assertEquals(DocumentType.AADHAAR, front.type)
        assertEquals("Priya Verma", front.fields[DocField.FULL_NAME])
        assertEquals("02/11/1988", front.fields[DocField.DATE_OF_BIRTH])
        assertEquals("Female", front.fields[DocField.GENDER])
        assertEquals("4567 8901 2345", front.fields[DocField.AADHAAR])
        assertNull(front.fields[DocField.PHONE])

        val back = DocumentExtractor.extract(
            ocr(
                """
                Unique Identification Authority of India
                Address: S/O Ramesh Kumar, 12 MG Road,
                Andheri East, Mumbai,
                Maharashtra - 400069
                4567 8901 2345
                """,
            ),
        )
        assertEquals("Ramesh Kumar", back.fields[DocField.FATHER_NAME])
        assertEquals("12 MG Road, Andheri East, Mumbai, Maharashtra - 400069", back.fields[DocField.ADDRESS])
        assertEquals("400069", back.fields[DocField.PINCODE])
    }

    @Test
    fun `reads bills, bank papers, licences and voter ids`() {
        val bill = DocumentExtractor.extract(
            ocr(
                """
                MAHAVITARAN
                Consumer No: 170012345678
                Name: ANIL PATIL
                Address: Flat 4, Shivaji Nagar, Pune 411005
                Mobile: +91 98220 12345
                Email: Anil.Patil@Example.com
                """,
            ),
        )
        assertEquals(DocumentType.OTHER, bill.type)
        assertEquals("Anil Patil", bill.fields[DocField.FULL_NAME])
        assertEquals("9822012345", bill.fields[DocField.PHONE])
        assertEquals("anil.patil@example.com", bill.fields[DocField.EMAIL])
        assertEquals("Flat 4, Shivaji Nagar, Pune 411005", bill.fields[DocField.ADDRESS])
        assertEquals("411005", bill.fields[DocField.PINCODE])

        val bank = DocumentExtractor.extract(ocr("STATE BANK OF INDIA\nBranch: Kothrud\nIFSC: SBIN0001234\nA/c No: 3012 4567 8901\nName: MEERA JOSHI"))
        assertEquals(DocumentType.BANK, bank.type)
        assertEquals("SBIN0001234", bank.fields[DocField.IFSC])
        assertEquals("301245678901", bank.fields[DocField.ACCOUNT_NUMBER])
        assertEquals("Meera Joshi", bank.fields[DocField.FULL_NAME])

        val dl = DocumentExtractor.extract(ocr("INDIAN UNION DRIVING LICENCE\nMH12 20110012345\nName: VIKRAM RAO\nDOB: 05-06-1985"))
        assertEquals(DocumentType.DRIVING_LICENCE, dl.type)
        assertEquals("MH1220110012345", dl.fields[DocField.DRIVING_LICENCE])
        assertEquals("05/06/1985", dl.fields[DocField.DATE_OF_BIRTH])
        assertEquals("Vikram Rao", dl.fields[DocField.FULL_NAME])

        val voter = DocumentExtractor.extract(ocr("ELECTION COMMISSION OF INDIA\nABC1234567\nElector's Name: SUNITA DEVI"))
        assertEquals("ABC1234567", voter.fields[DocField.VOTER_ID])
        assertEquals("Sunita Devi", voter.fields[DocField.FULL_NAME])

        assertTrue(DocumentExtractor.extract(ocr("hello world\n12 34")).isEmpty)
    }

    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Full name", FieldType.NAME)
    private val first = ScreenElement("vid:first", ElementKind.TEXT_FIELD, "First name", FieldType.NAME)
    private val last = ScreenElement("vid:last", ElementKind.TEXT_FIELD, "Surname", FieldType.NAME)
    private val father = ScreenElement("vid:father", ElementKind.TEXT_FIELD, "Father's name", FieldType.NAME)
    private val dob = ScreenElement("vid:dob", ElementKind.TEXT_FIELD, "Date of birth", FieldType.DATE)
    private val pan = ScreenElement("vid:pan", ElementKind.TEXT_FIELD, "PAN number")
    private val email = ScreenElement("vid:email", ElementKind.TEXT_FIELD, "Email address", FieldType.EMAIL)
    private val password = ScreenElement("vid:pwd", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, isSensitive = true)
    private val company = ScreenElement("vid:company", ElementKind.TEXT_FIELD, "Company", FieldType.TEXT)
    private val form = ScreenSnapshot("com.kyc", elements = listOf(name, first, last, father, dob, pan, email, password, company), signature = "kyc")

    @Test
    fun `maps document values to the screen's fields and never to passwords`() {
        val fills = DocumentFieldMapper.map(form, DocumentExtractor.extract(panCard)).associate { it.element.id to it.value }
        assertEquals(
            mapOf(
                "vid:name" to "Rahul Sharma", "vid:first" to "Rahul", "vid:last" to "Sharma", "vid:father" to "Suresh Sharma",
                "vid:dob" to "15/08/1990", "vid:pan" to "ABCPS1234K",
            ),
            fills,
        )
        // Fields that already hold the value are left alone.
        val filled = form.copy(elements = form.elements.map { if (it.id == "vid:pan") it.copy(value = "ABCPS1234K") else it })
        assertFalse(DocumentFieldMapper.map(filled, DocumentExtractor.extract(panCard)).any { it.element.id == "vid:pan" })
        assertEquals("XXXX XXXX 2345", DocumentFieldMapper.display(DocField.AADHAAR, "4567 8901 2345"))
        assertEquals("Rahul", DocumentFieldMapper.display(DocField.FULL_NAME, "Rahul"))
    }

    @Test
    fun `fills only when the original screen is back`() = runTest {
        val fills = DocumentFieldMapper.map(form, DocumentExtractor.extract(panCard))
        val elsewhere = FakeScreen(ScreenSnapshot("com.voicecontrol", elements = emptyList(), signature = "scan"))
        val missed = DocumentFiller(elsewhere).fill(form, fills, timeoutMillis = 1_000)
        assertFalse(missed.screenReturned)
        assertTrue(elsewhere.actions.isEmpty())

        val back = FakeScreen(form)
        val report = DocumentFiller(back).fill(form, fills)
        assertEquals(fills.size, report.filled)
        assertEquals("ABCPS1234K", back.valueOf("vid:pan"))
        assertTrue(back.actions.none { it is ScreenAction.SetText && it.elementId == "vid:pwd" })
    }

    @Test
    fun `orders recognised lines and prefers the Latin reading of English`() {
        val deva = listOf(
            OcrBox("नाम / Name", 10, 100, 200, 130),
            OcrBox("RAHUL SHARNA", 10, 140, 260, 170),
            OcrBox("आयकर विभाग", 10, 10, 200, 40),
        )
        val latin = listOf(
            OcrBox("RAHUL SHARMA", 12, 141, 258, 171),
            OcrBox("DOB:", 10, 200, 70, 230),
            OcrBox("15/08/1990", 90, 202, 220, 232),
        )
        assertEquals(listOf("आयकर विभाग", "नाम / Name", "RAHUL SHARMA", "DOB: 15/08/1990"), OcrLayout.merge(deva, latin).lines)
    }
}
