package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind

/** Kinds of things on screen the narrator can jump between. */
enum class ItemKind {
    BUTTON, FIELD, SWITCH, TEXT;

    fun matches(item: ReaderItem): Boolean = when (this) {
        TEXT -> item is ReaderItem.Text
        BUTTON -> item is ReaderItem.Element && (item.element.kind == ElementKind.BUTTON || item.element.kind == ElementKind.LINK)
        FIELD -> item is ReaderItem.Element && item.element.kind.isInput
        SWITCH -> item is ReaderItem.Element && item.element.kind.isToggle
    }
}

/** Screen-reader commands beyond next/previous/select (English, Hindi, Hinglish and Marathi words). */
sealed interface NarratorCommand {
    /** "next button", "agla khana", "previous heading". */
    data class Jump(val kind: ItemKind, val forward: Boolean) : NarratorCommand
    /** "top" / "last". */
    data class Edge(val first: Boolean) : NarratorCommand
    /** "where am I": screen name, position and what is on it. */
    data object WhereAmI : NarratorCommand
    /** "find delivery address", "OTP kahan hai". */
    data class Find(val query: String) : NarratorCommand
    /** "faster" / "slower". */
    data class Rate(val faster: Boolean) : NarratorCommand
    /** Read the rest of the screen and keep scrolling down to the end of the page. */
    data object ReadEverything : NarratorCommand
}

object NarratorCommands {
    private val forward = setOf("next", "agla", "agle", "agli", "aagla", "अगला", "अगले", "अगली", "पुढचा", "पुढील", "पुढे")
    private val backward = setOf("previous", "prev", "back to", "pichhla", "pichla", "pichhle", "pichle", "पिछला", "पिछले", "पिछली", "मागचा", "मागील")
    private val kinds: List<Pair<ItemKind, Set<String>>> = listOf(
        ItemKind.BUTTON to setOf("button", "buttons", "link", "बटन", "बटण"),
        ItemKind.FIELD to setOf("field", "fields", "box", "khana", "khaana", "खाना", "खाने", "फ़ील्ड", "फील्ड", "रकाना"),
        ItemKind.SWITCH to setOf("switch", "checkbox", "toggle", "option", "स्विच", "विकल्प", "पर्याय"),
        ItemKind.TEXT to setOf("text", "heading", "line", "likha", "लिखा", "पाठ", "मजकूर"),
    )
    private val first = setOf("top", "first", "first item", "go to top", "start of screen", "pehla", "sabse upar", "upar jao", "पहला", "सबसे ऊपर", "सुरुवात")
    private val last = setOf("bottom", "last", "last item", "go to bottom", "end of screen", "aakhri", "akhri", "sabse neeche", "आखिरी", "सबसे नीचे", "शेवटचा")
    private val whereAmI = setOf("where am i", "what screen is this", "kahan hoon", "kahan hu", "main kahan hoon", "main kahan hu", "मैं कहाँ हूँ", "कहाँ हूँ", "मी कुठे आहे")
    private val faster = setOf("faster", "speak faster", "speed up", "tez", "tez bolo", "jaldi bolo", "तेज़", "तेज", "तेज़ बोलो", "जल्दी बोलो", "वेगाने बोला")
    private val slower = setOf("slower", "speak slower", "slow down", "dheere", "dhire", "dheere bolo", "aaram se", "धीरे", "धीरे बोलो", "आराम से", "हळू बोला")
    private val everything = setOf(
        "read everything", "read whole page", "read the whole page", "read full page", "read the full page", "read entire page",
        "poora padho", "pura padho", "sab padho", "poora page padho", "पूरा पढ़ो", "सब पढ़ो", "पूरा पेज पढ़ो", "सगळे वाचा",
    )
    private val findPrefixes = listOf("find ", "search for ", "search ", "go to ", "jump to ", "dhundo ", "khojo ", "ढूंढो ", "खोजो ", "शोधा ")
    private val findSuffixes = listOf(" kahan hai", " kaha hai", " dhundo", " khojo", " कहाँ है", " ढूंढो", " खोजो", " कुठे आहे")

    fun parse(text: String): NarratorCommand? {
        val t = text.lowercase().replace(Regex("[?.!,।]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (t in everything) return NarratorCommand.ReadEverything
        if (t in whereAmI) return NarratorCommand.WhereAmI
        if (t in faster) return NarratorCommand.Rate(faster = true)
        if (t in slower) return NarratorCommand.Rate(faster = false)
        if (t in first) return NarratorCommand.Edge(first = true)
        if (t in last) return NarratorCommand.Edge(first = false)
        val words = t.split(' ')
        val kind = kinds.firstOrNull { (_, names) -> words.any { it in names } }?.first
        if (kind != null && words.size <= 4) {
            if (words.any { it in forward }) return NarratorCommand.Jump(kind, forward = true)
            if (words.any { it in backward } || backward.any { " $it " in " $t " }) return NarratorCommand.Jump(kind, forward = false)
        }
        findPrefixes.firstOrNull { t.startsWith(it) }?.let { p -> t.removePrefix(p).trim().takeIf { it.length >= 2 }?.let { return NarratorCommand.Find(it) } }
        findSuffixes.firstOrNull { t.endsWith(it) }?.let { s -> t.removeSuffix(s).trim().takeIf { it.length >= 2 }?.let { return NarratorCommand.Find(it) } }
        return null
    }

    /** Index of the next (or previous) item of [kind] from [from], or null. */
    fun jump(items: List<ReaderItem>, from: Int, kind: ItemKind, forward: Boolean): Int? {
        val range = if (forward) (from + 1..items.lastIndex) else (from - 1 downTo 0)
        return range.firstOrNull { kind.matches(items[it]) }
    }

    /** Next item (after [from], wrapping around) whose spoken text contains every word of [query]. */
    fun find(items: List<ReaderItem>, from: Int, query: String, describe: (ReaderItem) -> String): Int? {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || items.isEmpty()) return null
        val order = (1..items.size).map { (from + it) % items.size }
        return order.firstOrNull { i -> describe(items[i]).lowercase().let { text -> words.all { it in text } } }
    }
}
