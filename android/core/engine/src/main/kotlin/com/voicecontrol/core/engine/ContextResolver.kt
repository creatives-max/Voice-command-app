package com.voicecontrol.core.engine

import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.UserProfile
import com.voicecontrol.core.screen.LabelText

/** An answer given earlier in the session (never a sensitive value). */
data class MemoryItem(val label: String, val fieldType: FieldType?, val value: String)

/**
 * Resolves answers that refer to earlier context instead of saying a value:
 * - "same as above", "same", "wahi", "वही", "तेच", "அதே", "అదే", "একই", "એ જ" → the latest earlier answer for a
 *   field of the same type (else the latest answer),
 * - "same as permanent address", "same as father's name" → the earlier answer whose label matches,
 * - "my email", "use my phone", "mera naam", "मेरा ईमेल" → the user's profile value.
 * Works offline in every supported language; the backend LLM also receives the memory for harder cases.
 */
object ContextResolver {

    private val sameMarkers = setOf(
        "same", "the same", "same as above", "same as before", "same as previous", "same as earlier", "as above", "ditto", "same one", "that one", "same thing",
        "wahi", "vahi", "wohi", "vohi", "same wala", "upar wala", "upar jaisa", "pehle wala", "wahi wala",
        "वही", "वोही", "ऊपर वाला", "ऊपर जैसा", "पहले वाला", "वही वाला",
        "तेच", "तोच", "तीच", "वरीलप्रमाणे", "वरच्यासारखे",
        "அதே", "அதுவே", "மேலே உள்ளது போல",
        "అదే", "పైన ఉన్నట్టే", "పైది",
        "একই", "উপরের মত", "উপরের মতো", "আগের মত", "আগের মতো",
        "એ જ", "તે જ", "ઉપર મુજબ", "પહેલાં જેવું",
    )

    private val samePrefixes = listOf("same as", "same like", "use the same as", "copy from", "copy")
    private val possessives = setOf("my", "use my", "mera", "meri", "mere", "apna", "apni", "मेरा", "मेरी", "मेरे", "अपना", "अपनी", "माझा", "माझी", "माझे", "என்", "என்னுடைய", "నా", "আমার", "મારો", "મારું", "મારી")
    private val filler = setOf("the", "above", "before", "previous", "one", "field", "wala", "wali", "वाला", "वाली")

    private val profileWords: Map<String, ProfileKey> = buildMap {
        listOf("name", "full name", "naam", "नाम", "नाव", "பெயர்", "పేరు", "নাম", "નામ").forEach { put(it, ProfileKey.FULL_NAME) }
        listOf("email", "email id", "mail", "ईमेल", "மின்னஞ்சல்", "ఈమెయిల్", "ইমেল", "ઈમેલ").forEach { put(it, ProfileKey.EMAIL) }
        listOf("phone", "number", "mobile", "mobile number", "phone number", "नंबर", "मोबाइल", "मोबाईल", "எண்", "நம்பர்", "నంబర్", "নম্বর", "નંબર").forEach { put(it, ProfileKey.PHONE) }
        listOf("address", "pata", "पता", "पत्ता", "முகவரி", "చిరునామా", "ঠিকানা", "સરનામું").forEach { put(it, ProfileKey.ADDRESS_LINE) }
        listOf("city", "shahar", "शहर", "शहर", "நகரம்", "నగరం", "শহর", "શહેર").forEach { put(it, ProfileKey.CITY) }
        listOf("state", "राज्य").forEach { put(it, ProfileKey.STATE) }
        listOf("pincode", "pin code", "pin", "पिन कोड", "पिनकोड").forEach { put(it, ProfileKey.PINCODE) }
        listOf("date of birth", "dob", "birthday", "जन्मतिथि", "जन्म तिथि").forEach { put(it, ProfileKey.DATE_OF_BIRTH) }
    }

    /** The value [utterance] refers to for [field], or null when it isn't a reference. */
    fun resolve(utterance: String, field: ScreenElement, memory: List<MemoryItem>, profile: UserProfile?): String? {
        if (field.isSensitive) return null
        val s = simplify(utterance)
        if (s.isEmpty()) return null
        if (s in sameMarkers) return latest(field, memory)
        profileReference(s, profile)?.let { return it }
        for (prefix in samePrefixes) {
            if (!s.startsWith("$prefix ")) continue
            val rest = s.removePrefix("$prefix ").split(' ').filter { it.isNotEmpty() && it !in filler }
            if (rest.isEmpty()) return latest(field, memory)
            profileReference(rest.joinToString(" "), profile)?.let { return it }
            return byLabel(rest, memory) ?: latest(field, memory).takeIf { rest.all { w -> w in setOf("above", "before", "upar", "ऊपर") } }
        }
        return null
    }

    private fun latest(field: ScreenElement, memory: List<MemoryItem>): String? =
        memory.lastOrNull { field.fieldType != null && it.fieldType == field.fieldType }?.value ?: memory.lastOrNull()?.value

    private fun byLabel(words: List<String>, memory: List<MemoryItem>): String? {
        val best = memory.map { item ->
            val label = LabelText.normalize(item.label).split(' ').toSet()
            item to words.count { w -> label.any { it == w || it.startsWith(w) || w.startsWith(it) && it.length >= 3 } }
        }.filter { it.second > 0 }.maxWithOrNull(compareBy<Pair<MemoryItem, Int>> { it.second }.thenBy { memory.indexOf(it.first) })
        return best?.first?.value
    }

    private fun profileReference(s: String, profile: UserProfile?): String? {
        profile ?: return null
        val possessive = possessives.sortedByDescending { it.length }.firstOrNull { s.startsWith("$it ") } ?: return null
        val noun = s.removePrefix("$possessive ").removeSuffix(" use karo").removeSuffix(" daalo").removeSuffix(" wala").trim()
        val key = profileWords[noun] ?: return null
        return profile.value(key)
    }

    private fun simplify(s: String): String =
        s.lowercase().replace(Regex("[\\p{P}&&[^']]"), " ").replace("'s", "").replace(Regex("\\s+"), " ").trim()
}
