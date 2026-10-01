package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.delay

/** Kinds of Indian identity and everyday documents VoiceControl can read values from. */
enum class DocumentType(val label: String) {
    PAN_CARD("PAN card"),
    AADHAAR("Aadhaar card"),
    DRIVING_LICENCE("Driving licence"),
    VOTER_ID("Voter ID"),
    PASSPORT("Passport"),
    BANK("Bank document"),
    OTHER("Document"),
}

/** A value read from a document. [AADHAAR] is shown masked until the user reveals it. */
enum class DocField(val label: String, val masked: Boolean = false) {
    FULL_NAME("Name"),
    FATHER_NAME("Father's name"),
    DATE_OF_BIRTH("Date of birth"),
    GENDER("Gender"),
    PAN("PAN"),
    AADHAAR("Aadhaar number", masked = true),
    PHONE("Mobile number"),
    EMAIL("Email"),
    ADDRESS("Address"),
    PINCODE("PIN code"),
    IFSC("IFSC"),
    ACCOUNT_NUMBER("Account number", masked = true),
    PASSPORT("Passport number"),
    VOTER_ID("Voter ID (EPIC)"),
    DRIVING_LICENCE("Driving licence number"),
    VEHICLE_NUMBER("Vehicle number"),
}

/** Text recognised in a photo, top to bottom. */
data class OcrText(val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
}

data class ExtractedDocument(val type: DocumentType, val fields: Map<DocField, String>) {
    val isEmpty: Boolean get() = fields.isEmpty()
}

/**
 * Reads values from OCR text of Indian documents (PAN, Aadhaar, driving licence, voter ID, passport,
 * cheques and passbooks) and of everyday papers (bills, letters, visiting cards). Everything runs on
 * the phone; nothing here stores or sends the values.
 */
object DocumentExtractor {

    private val PAN = Regex("\\b([A-Z]{5}[0-9]{4}[A-Z])\\b")
    private val AADHAAR = Regex("(?<![0-9])([2-9][0-9]{3}) ?([0-9]{4}) ?([0-9]{4})(?![0-9])")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![0-9])(?:\\+?91[ -]?)?([6-9][0-9]{4}) ?([0-9]{5})(?![0-9])")
    private val PINCODE = Regex("(?<![0-9])([1-9][0-9]{2}) ?([0-9]{3})(?![0-9])")
    private val DATE = Regex("(?<![0-9])([0-3]?[0-9])[/.-]([01]?[0-9])[/.-]((?:19|20)[0-9]{2})(?![0-9])")
    private val YEAR_OF_BIRTH = Regex("(?:year of birth|yob|जन्म वर्ष)\\s*[:/-]?\\s*((?:19|20)[0-9]{2})", RegexOption.IGNORE_CASE)
    private val IFSC = Regex("\\b([A-Z]{4}0[A-Z0-9]{6})\\b")
    private val ACCOUNT = Regex(
        "(?:\\ba/c|\\baccount|\\bacct|खाता)\\.?\\s*(?:no|number|num|सं|संख्या)?\\.?\\s*[:.-]?\\s*([0-9][0-9 ]{7,20}[0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val PASSPORT = Regex("\\b([A-PR-WY][1-9][0-9]{6})\\b")
    private val VOTER = Regex("\\b([A-Z]{3}[0-9]{7})\\b")
    private val LICENCE = Regex("\\b([A-Z]{2}[0-9]{2})[ -]?((?:19|20)[0-9]{2})[ -]?([0-9]{7})\\b")
    private val VEHICLE = Regex("\\b([A-Z]{2})[ -]?([0-9]{1,2})[ -]?([A-Z]{1,3})[ -]?([0-9]{4})\\b")

    private val nameLabels = listOf("name", "नाम", "नाव", "பெயர்", "పేరు", "নাম", "નામ")
    private val fatherLabels = listOf("father", "पिता", "s/o", "d/o", "w/o", "son of", "daughter of")
    private val dobLabels = listOf("dob", "d.o.b", "date of birth", "birth", "जन्म", "जन्म तिथि", "जन्मतारीख")
    private val addressLabels = listOf("address", "पता", "पत्ता", "முகவரி", "చిరునామా", "ঠিকানা", "સરનામું")
    /** Lines that are headings or field labels, never a person's name. */
    private val notNames = listOf(
        "government", "india", "income tax", "department", "permanent account", "card", "signature", "aadhaar", "आधार", "भारत",
        "सरकार", "male", "female", "पुरुष", "महिला", "dob", "birth", "address", "पता", "election", "commission", "licence", "license",
        "republic", "passport", "bank", "branch", "ifsc", "account", "union", "authority", "unique", "identification", "father", "पिता",
        "vid", "enrolment", "help", "www", "@", "name", "नाम",
    )

    /** Label words left over after the label itself ("Father's Name:", "का नाम"). */
    private val LABEL_REST = Regex("^(?:['’]s\\b|\\bname\\b|नाम|का|की|[:/.\\-\\s])*", RegexOption.IGNORE_CASE)

    fun extract(ocr: OcrText): ExtractedDocument {
        val lines = ocr.lines.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val all = lines.joinToString("\n")
        val upper = all.uppercase()
        val fields = linkedMapOf<DocField, String>()

        val type = when {
            "INCOME TAX" in upper || "PERMANENT ACCOUNT" in upper -> DocumentType.PAN_CARD
            "AADHAAR" in upper || "आधार" in all || "UNIQUE IDENTIFICATION" in upper -> DocumentType.AADHAAR
            "DRIVING LICEN" in upper -> DocumentType.DRIVING_LICENCE
            "ELECTION COMMISSION" in upper || "ELECTOR" in upper -> DocumentType.VOTER_ID
            "PASSPORT" in upper -> DocumentType.PASSPORT
            IFSC.containsMatchIn(upper) || "BANK" in upper -> DocumentType.BANK
            else -> DocumentType.OTHER
        }

        PAN.find(upper)?.let { fields[DocField.PAN] = it.groupValues[1] }
        if (type == DocumentType.AADHAAR || "AADHAAR" in upper || "आधार" in all) {
            // VIDs are 16 digits; the Aadhaar number is the 12-digit group, usually printed as 4-4-4.
            lines.firstNotNullOfOrNull { line ->
                AADHAAR.find(line)?.takeIf { !Regex("[0-9]{4} ?[0-9]{4} ?[0-9]{4} ?[0-9]{4}").containsMatchIn(line) }
            }?.let { m -> fields[DocField.AADHAAR] = "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}" }
        }
        EMAIL.find(all)?.let { fields[DocField.EMAIL] = it.value.lowercase() }
        lines.firstNotNullOfOrNull { line ->
            if (AADHAAR.containsMatchIn(line) && DocField.AADHAAR in fields) null else PHONE.find(line)
        }?.let { fields[DocField.PHONE] = it.groupValues[1] + it.groupValues[2] }

        dateOfBirth(lines)?.let { fields[DocField.DATE_OF_BIRTH] = it }
        gender(all)?.let { fields[DocField.GENDER] = it }
        IFSC.find(upper)?.let { fields[DocField.IFSC] = it.groupValues[1] }
        ACCOUNT.find(all)?.let { fields[DocField.ACCOUNT_NUMBER] = it.groupValues[1].replace(" ", "") }
        if (type == DocumentType.PASSPORT) PASSPORT.find(upper)?.let { fields[DocField.PASSPORT] = it.groupValues[1] }
        if (type == DocumentType.VOTER_ID) VOTER.find(upper)?.let { fields[DocField.VOTER_ID] = it.groupValues[1] }
        LICENCE.find(upper)?.takeIf { type == DocumentType.DRIVING_LICENCE }?.let {
            fields[DocField.DRIVING_LICENCE] = "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}"
        }
        if (type == DocumentType.OTHER || type == DocumentType.BANK) {
            VEHICLE.find(upper)?.takeIf { PAN.find(upper)?.value != it.value }?.let {
                fields[DocField.VEHICLE_NUMBER] = it.groupValues.drop(1).joinToString("")
            }
        }

        labelledValue(lines, fatherLabels)?.let { fields[DocField.FATHER_NAME] = titleCase(it) }
        name(lines, type, fields[DocField.FATHER_NAME])?.let { fields[DocField.FULL_NAME] = it }
        address(lines)?.let { (text, pin) ->
            fields[DocField.ADDRESS] = text
            pin?.let { fields[DocField.PINCODE] = it }
        }
        if (DocField.PINCODE !in fields) {
            lines.filter { line -> addressLabels.any { line.lowercase().contains(it) } || ',' in line }
                .firstNotNullOfOrNull { PINCODE.find(it) }
                ?.let { fields[DocField.PINCODE] = it.groupValues[1] + it.groupValues[2] }
        }
        return ExtractedDocument(type, fields)
    }

    private fun dateOfBirth(lines: List<String>): String? {
        val labelled = lines.indices.firstNotNullOfOrNull { i ->
            val line = lines[i]
            if (dobLabels.none { line.lowercase().contains(it) }) return@firstNotNullOfOrNull null
            DATE.find(line) ?: lines.getOrNull(i + 1)?.let { DATE.find(it) }
        }
        val m = labelled ?: lines.firstNotNullOfOrNull { DATE.find(it) } ?: return YEAR_OF_BIRTH.find(lines.joinToString("\n"))?.groupValues?.get(1)
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (day !in 1..31 || month !in 1..12) return null
        return "%02d/%02d/%s".format(day, month, m.groupValues[3])
    }

    private fun gender(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("\\bfemale\\b").containsMatchIn(t) || "महिला" in text -> "Female"
            Regex("\\bmale\\b").containsMatchIn(t) || "पुरुष" in text -> "Male"
            Regex("\\btransgender\\b").containsMatchIn(t) -> "Transgender"
            else -> null
        }
    }

    /** Value after a label on the same line ("Name: Rahul") or on the next line. */
    private fun labelledValue(lines: List<String>, labels: List<String>): String? {
        lines.forEachIndexed { i, line ->
            val lower = line.lowercase()
            val label = labels.firstOrNull { lower.contains(it) } ?: return@forEachIndexed
            val after = LABEL_REST.replace(line.substring(lower.indexOf(label) + label.length), "").substringBefore(',').trim()
            val candidate = after.takeIf { looksLikeName(it) } ?: lines.getOrNull(i + 1)?.substringBefore(',')?.takeIf { looksLikeName(it) }
            if (candidate != null) return candidate.trim()
        }
        return null
    }

    private fun name(lines: List<String>, type: DocumentType, father: String?): String? {
        val labelled = lines.withIndex().firstNotNullOfOrNull { (i, line) ->
            val lower = line.lowercase()
            if (fatherLabels.any { lower.contains(it) }) return@firstNotNullOfOrNull null
            val label = nameLabels.firstOrNull { Regex("(^|[^a-z])${Regex.escape(it)}([^a-z]|$)").containsMatchIn(lower) } ?: return@firstNotNullOfOrNull null
            val after = LABEL_REST.replace(line.substring(lower.indexOf(label) + label.length), "").trim()
            (after.takeIf { looksLikeName(it) } ?: lines.getOrNull(i + 1)?.takeIf { looksLikeName(it) && !it.lowercase().let { n -> fatherLabels.any { f -> f in n } } })
        }
        val guess = labelled ?: when (type) {
            // Aadhaar: the name is the line just above the date of birth.
            // Aadhaar: the name is just above the date of birth, often in English and in the local script.
            DocumentType.AADHAAR -> lines.indexOfFirst { l -> dobLabels.any { l.lowercase().contains(it) } || YEAR_OF_BIRTH.containsMatchIn(l) }
                .takeIf { it > 0 }?.let { idx ->
                    val above = (idx - 1 downTo maxOf(0, idx - 3)).map { lines[it] }.filter { looksLikeName(it) }
                    above.firstOrNull { n -> n.any { it in 'A'..'Z' || it in 'a'..'z' } } ?: above.firstOrNull()
                }
            // PAN: the first name-like line after the department heading; the father's name follows it.
            DocumentType.PAN_CARD -> lines.firstOrNull { looksLikeName(it) }
            else -> null
        }
        return guess?.let(::titleCase)?.takeIf { it != father }
    }

    private fun looksLikeName(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 3..60) return false
        val lower = t.lowercase()
        if (notNames.any { lower.contains(it) }) return false
        if (t.any { it.isDigit() }) return false
        val words = t.split(' ').filter { it.isNotEmpty() }
        if (words.size !in 1..5) return false
        return t.all { it.isLetter() || it == ' ' || it == '.' || it == '\'' || Character.getType(it) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() }
    }

    private fun titleCase(s: String): String =
        s.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            if (w.all { !it.isLetter() || it.isUpperCase() } && w.any { it in 'A'..'Z' }) w.lowercase().replaceFirstChar { it.uppercase() } else w
        }

    /** Text after an address label up to and including the line with the PIN code. */
    private fun address(lines: List<String>): Pair<String, String?>? {
        val start = lines.indexOfFirst { l -> addressLabels.any { l.lowercase().startsWith(it) || l.lowercase().contains("$it:") } }
        if (start < 0) return null
        val first = lines[start].let { l ->
            val lower = l.lowercase()
            val label = addressLabels.first { lower.contains(it) }
            l.substring(lower.indexOf(label) + label.length).trimStart(' ', ':', '-').trim()
        }
        val parts = mutableListOf<String>()
        // "S/O Ramesh Kumar, 12 MG Road": the relation is not part of the address.
        val own = if (Regex("^(?:s|d|w|c)/o\\b", RegexOption.IGNORE_CASE).containsMatchIn(first)) first.substringAfter(',', "").trim() else first
        if (own.isNotEmpty()) parts += own
        var pin: String? = PINCODE.find(first)?.let { it.groupValues[1] + it.groupValues[2] }
        var i = start + 1
        while (pin == null && i < lines.size && parts.size < 6) {
            val line = lines[i]
            if (AADHAAR.containsMatchIn(line) && line.replace(" ", "").length <= 14) break
            parts += line
            pin = PINCODE.find(line)?.let { it.groupValues[1] + it.groupValues[2] }
            i++
        }
        if (parts.isEmpty()) return null
        val text = parts.joinToString(", ").replace(Regex(",\\s*,"), ",").replace(Regex("\\s+"), " ").trim(' ', ',')
        return text to pin
    }
}

/** A value from a document proposed for one field of the screen. */
data class ProposedFill(val element: ScreenElement, val field: DocField, val value: String)

/** Matches document values to the fields of a screen, by field type and label (English and Indian languages). */
object DocumentFieldMapper {

    private val rules: List<Pair<List<String>, DocField>> = listOf(
        listOf("father", "पिता", "guardian") to DocField.FATHER_NAME,
        listOf("pan") to DocField.PAN,
        listOf("aadhaar", "aadhar", "uid", "आधार") to DocField.AADHAAR,
        listOf("ifsc") to DocField.IFSC,
        listOf("account number", "account no", "a/c", "खाता") to DocField.ACCOUNT_NUMBER,
        listOf("passport") to DocField.PASSPORT,
        listOf("voter", "epic") to DocField.VOTER_ID,
        listOf("licence", "license", "dl number") to DocField.DRIVING_LICENCE,
        listOf("vehicle", "registration number", "rc number") to DocField.VEHICLE_NUMBER,
        listOf("birth", "dob", "जन्म") to DocField.DATE_OF_BIRTH,
        listOf("gender", "लिंग") to DocField.GENDER,
        listOf("email", "e-mail", "ईमेल") to DocField.EMAIL,
        listOf("mobile", "phone", "मोबाइल", "फ़ोन") to DocField.PHONE,
        listOf("pin code", "pincode", "postal code", "zip", "पिन") to DocField.PINCODE,
        listOf("address", "पता", "पत्ता") to DocField.ADDRESS,
        listOf("name", "नाम", "नाव") to DocField.FULL_NAME,
    )

    fun map(snapshot: ScreenSnapshot, doc: ExtractedDocument): List<ProposedFill> =
        snapshot.elements.filter(::fillable).mapNotNull { element ->
            val field = fieldFor(element) ?: return@mapNotNull null
            val value = valueFor(element, field, doc) ?: return@mapNotNull null
            if (element.value?.trim() == value) null else ProposedFill(element, field, value)
        }

    private fun fillable(e: ScreenElement) =
        e.kind == ElementKind.TEXT_FIELD && e.isEnabled && !e.isSensitive && e.fieldType?.isSensitive != true &&
            !e.id.startsWith(VisionDetector.VISION_ID_PREFIX)

    fun fieldFor(e: ScreenElement): DocField? {
        val label = listOfNotNull(e.label, e.hint).joinToString(" ").lowercase()
        rules.firstOrNull { (words, _) -> words.any { Regex("(^|[^a-z])${Regex.escape(it)}").containsMatchIn(label) } }?.let { return it.second }
        return when (e.fieldType) {
            FieldType.EMAIL -> DocField.EMAIL
            FieldType.PHONE -> DocField.PHONE
            FieldType.PINCODE -> DocField.PINCODE
            FieldType.NAME -> DocField.FULL_NAME
            FieldType.ADDRESS -> DocField.ADDRESS
            else -> null
        }
    }

    private fun valueFor(e: ScreenElement, field: DocField, doc: ExtractedDocument): String? {
        val label = listOfNotNull(e.label, e.hint).joinToString(" ").lowercase()
        val value = doc.fields[field] ?: return null
        if (field != DocField.FULL_NAME) return value
        val words = value.split(' ').filter { it.isNotEmpty() }
        return when {
            "first" in label || "given" in label -> words.first()
            "last" in label || "surname" in label || "family" in label -> words.drop(1).lastOrNull()
            "middle" in label -> words.drop(1).dropLast(1).joinToString(" ").ifEmpty { null }
            else -> value
        }
    }

    /** "XXXX XXXX 1234" for masked fields, the value otherwise. */
    fun display(field: DocField, value: String): String =
        if (!field.masked) value else value.mapIndexed { i, c -> if (c.isDigit() && i < value.length - 4) 'X' else c }.joinToString("")
}

/**
 * Fills reviewed document values into the app the user came from, once its screen is back in front.
 * Fields are found again by id, so a screen that changed in the meantime is never filled blindly.
 */
class DocumentFiller(private val screen: ScreenGateway, private val pollMillis: Long = 250L) {

    data class Report(val filled: Int, val failed: Int, val screenReturned: Boolean)

    suspend fun fill(target: ScreenSnapshot, fills: List<ProposedFill>, timeoutMillis: Long = 10_000L): Report {
        var waited = 0L
        var current = screen.capture()
        while (current == null || current.packageName != target.packageName || current.signature != target.signature) {
            if (waited >= timeoutMillis) return Report(0, fills.size, screenReturned = false)
            delay(pollMillis)
            waited += pollMillis
            current = screen.capture()
        }
        var filled = 0
        fills.forEach { f ->
            val element = current.element(f.element.id)
            val ok = element != null && !element.isSensitive && screen.perform(ScreenAction.SetText(element.id, f.value)) is ActionResult.Success
            if (ok) filled++
        }
        return Report(filled, fills.size - filled, screenReturned = true)
    }
}

/** A line of recognised text and where it is in the photo. */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Int get() = (top + bottom) / 2

    fun overlap(other: OcrBox): Double {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (w <= 0 || h <= 0) return 0.0
        val inter = w.toDouble() * h
        val union = (right - left).toDouble() * height + (other.right - other.left).toDouble() * other.height - inter
        return if (union <= 0) 0.0 else inter / union
    }
}

/**
 * Turns the lines of two text recognisers (one for Devanagari, which also reads English, and one
 * tuned for Latin script) into reading order: duplicates are merged, preferring the Latin reading
 * of English lines, and boxes on the same row ("Name:" … "RAHUL") are joined left to right.
 */
object OcrLayout {
    private fun isDevanagari(c: Char) = c in '\u0900'..'\u097F'

    fun merge(devanagari: List<OcrBox>, latin: List<OcrBox>): OcrText {
        val boxes = devanagari.map { d ->
            val twin = latin.maxByOrNull { it.overlap(d) }?.takeIf { it.overlap(d) > 0.5 }
            if (twin != null && d.text.none(::isDevanagari)) twin else d
        }.toMutableList()
        latin.filter { l -> devanagari.none { it.overlap(l) > 0.5 } }.forEach { boxes += it }
        return OcrText(rows(boxes).map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }.filter { it.isNotBlank() })
    }

    private fun rows(boxes: List<OcrBox>): List<List<OcrBox>> {
        val rows = mutableListOf<MutableList<OcrBox>>()
        boxes.sortedBy { it.top }.forEach { box ->
            val row = rows.lastOrNull()?.takeIf { r ->
                val ref = r.first()
                kotlin.math.abs(ref.centerY - box.centerY) < minOf(ref.height, box.height) / 2
            }
            if (row != null) row += box else rows += mutableListOf(box)
        }
        return rows
    }
}
