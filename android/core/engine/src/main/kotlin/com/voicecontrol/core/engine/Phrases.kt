package com.voicecontrol.core.engine

import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language

/** Everything the assistant says, in English, Hindi and Hinglish. */
class Phrases(private val language: Language) {

    private fun t(en: String, hi: String, hinglish: String) = when (language) {
        Language.ENGLISH -> en
        Language.HINDI -> hi
        Language.HINGLISH -> hinglish
    }

    fun start(fieldCount: Int) = t(
        "I found $fieldCount fields. Let's fill them.",
        "मुझे $fieldCount खाने मिले। चलिए भरते हैं।",
        "Mujhe $fieldCount fields mile. Chaliye bharte hain.",
    )

    fun ask(label: String, type: FieldType?): String = when (type) {
        FieldType.EMAIL -> t("What is your email address?", "आपका ईमेल पता क्या है?", "Aapka email address kya hai?")
        FieldType.PHONE -> t("What is your mobile number?", "आपका मोबाइल नंबर क्या है?", "Aapka mobile number kya hai?")
        FieldType.DATE -> t("Please say the $label, like 12 March 1990.", "$label बताइए, जैसे 12 मार्च 1990।", "$label bataiye, jaise 12 March 1990.")
        else -> t("Please say $label.", "$label बताइए।", "$label bataiye.")
    }

    fun askToggle(label: String) = t(
        "Should I select \"$label\"? Say yes or no.",
        "क्या मैं \"$label\" चुनूं? हाँ या नहीं बोलिए।",
        "Kya main \"$label\" select karun? Haan ya nahi boliye.",
    )

    fun askDropdown(label: String) = t(
        "Which option for $label?",
        "$label के लिए कौन सा विकल्प?",
        "$label ke liye kaunsa option?",
    )

    fun askUseSuggested(question: String, value: String) = t(
        "$question Say yes to use $value.",
        "$question $value के लिए हाँ बोलिए।",
        "$question $value use karne ke liye haan boliye.",
    )

    fun keepExisting(label: String, value: String) = t(
        "$label already has $value. Keep it?",
        "$label में पहले से $value है। रखें?",
        "$label mein pehle se $value hai. Rakhein?",
    )

    fun sensitiveManual(label: String) = t(
        "For your safety, please type $label yourself. Say next when done.",
        "सुरक्षा के लिए $label खुद टाइप करें। हो जाए तो आगे बोलिए।",
        "Safety ke liye $label khud type kariye. Ho jaaye to next boliye.",
    )

    fun filled(value: String) = t("Got it, $value.", "ठीक है, $value।", "Theek hai, $value.")

    fun filledShort() = t("Done.", "हो गया।", "Ho gaya.")

    fun invalid(message: String) = t("$message. Please try again.", "$message। फिर से बोलिए।", "$message. Phir se boliye.")

    fun didNotCatch() = t("Sorry, I didn't catch that.", "माफ़ कीजिए, समझ नहीं आया।", "Sorry, samajh nahi aaya.")

    fun pausedNoSpeech() = t(
        "I'll pause now. Tap the mic when you're ready.",
        "मैं रुक रहा हूँ। तैयार हों तो माइक दबाइए।",
        "Main ruk raha hoon. Ready hon to mic dabaiye.",
    )

    fun confirmPress(button: String) = t(
        "All done. Shall I press $button?",
        "सब भर गया। क्या $button दबाऊं?",
        "Sab bhar gaya. Kya $button dabaun?",
    )

    fun whichButton(buttons: List<String>) = t(
        "Which button should I press? ${buttons.joinToString(", ")}.",
        "कौन सा बटन दबाऊं? ${buttons.joinToString(", ")}।",
        "Kaunsa button dabaun? ${buttons.joinToString(", ")}.",
    )

    fun nothingToFill() = t(
        "There is nothing to fill here. Say a button name, scroll, or back.",
        "यहाँ भरने को कुछ नहीं है। बटन का नाम, नीचे या वापस बोलिए।",
        "Yahan bharne ko kuch nahi hai. Button ka naam, scroll ya back boliye.",
    )

    fun pressed(label: String) = t("Pressed $label.", "$label दबा दिया।", "$label daba diya.")

    fun buttonNotFound(target: String) = t(
        "I couldn't find a button called $target.",
        "$target नाम का बटन नहीं मिला।",
        "$target naam ka button nahi mila.",
    )

    fun actionFailed() = t("That didn't work.", "यह नहीं हो पाया।", "Yeh nahi ho paya.")

    fun newScreen() = t("New screen.", "नई स्क्रीन।", "Nayi screen.")

    fun done() = t("All done.", "सब हो गया।", "Sab ho gaya.")

    fun stopped() = t("Stopped.", "रोक दिया।", "Rok diya.")

    fun cannotRead() = t(
        "I can't read this screen.",
        "मैं यह स्क्रीन नहीं पढ़ पा रहा।",
        "Main yeh screen nahi padh pa raha.",
    )

    fun lookingAtScreen() = t("Let me look at the screen.", "मैं स्क्रीन देख रहा हूँ।", "Main screen dekh raha hoon.")

    fun serviceOff() = t(
        "Please turn on the VoiceControl accessibility service.",
        "कृपया VoiceControl की सुलभता सेवा चालू करें।",
        "Please VoiceControl accessibility service on kariye.",
    )

    fun help() = t(
        "Say the answer, or say next, previous, skip, repeat, submit, scroll, back, or stop.",
        "जवाब बोलिए, या आगे, पिछला, छोड़ो, फिर से, जमा, नीचे, वापस या रुको बोलिए।",
        "Jawab boliye, ya next, pichla, skip, phir se, submit, scroll, back ya stop boliye.",
    )

    fun scrolled() = t("Scrolled.", "स्क्रॉल किया।", "Scroll kiya.")

    fun wentBack() = t("Going back.", "वापस जा रहे हैं।", "Wapas ja rahe hain.")

    fun cleared(label: String) = t("Cleared $label.", "$label मिटा दिया।", "$label mita diya.")
}
