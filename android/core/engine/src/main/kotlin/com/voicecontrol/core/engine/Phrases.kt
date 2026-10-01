package com.voicecontrol.core.engine

import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language

/** Everything the assistant says, in English, Hindi, Hinglish, Marathi, Tamil, Telugu, Bengali and Gujarati. */
class Phrases(private val language: Language) {

    @Suppress("LongParameterList")
    private fun t(en: String, hi: String, hinglish: String, mr: String, ta: String, te: String, bn: String, gu: String) = when (language) {
        Language.ENGLISH -> en
        Language.HINDI -> hi
        Language.HINGLISH -> hinglish
        Language.MARATHI -> mr
        Language.TAMIL -> ta
        Language.TELUGU -> te
        Language.BENGALI -> bn
        Language.GUJARATI -> gu
    }

    fun ask(label: String, type: FieldType?): String = when (type) {
        FieldType.EMAIL -> askEmail()
        FieldType.PHONE -> askPhone()
        FieldType.DATE -> askDate(label)
        else -> askField(label)
    }

    fun whichButton(buttons: List<String>) = whichButtonOf(buttons.joinToString(", "))


    fun start(fieldCount: Int) = t(
        "I found $fieldCount fields. Let's fill them.",
        "मुझे $fieldCount खाने मिले। चलिए भरते हैं।",
        "Mujhe $fieldCount fields mile. Chaliye bharte hain.",
        "मला $fieldCount रकाने सापडले. चला भरूया.",
        "$fieldCount புலங்கள் உள்ளன. நிரப்புவோம்.",
        "$fieldCount ఖాళీలు ఉన్నాయి. నింపుదాం.",
        "${fieldCount}টি ঘর পেয়েছি। চলুন পূরণ করি।",
        "મને $fieldCount ખાનાં મળ્યાં. ચાલો ભરીએ.",
    )

    private fun askEmail() = t(
        "What is your email address?",
        "आपका ईमेल पता क्या है?",
        "Aapka email address kya hai?",
        "तुमचा ईमेल पत्ता काय आहे?",
        "உங்கள் மின்னஞ்சல் முகவரி என்ன?",
        "మీ ఈమెయిల్ చిరునామా ఏమిటి?",
        "আপনার ইমেল ঠিকানা কী?",
        "તમારું ઈમેલ સરનામું શું છે?",
    )

    private fun askPhone() = t(
        "What is your mobile number?",
        "आपका मोबाइल नंबर क्या है?",
        "Aapka mobile number kya hai?",
        "तुमचा मोबाईल नंबर काय आहे?",
        "உங்கள் மொபைல் எண் என்ன?",
        "మీ మొబైల్ నంబర్ ఏమిటి?",
        "আপনার মোবাইল নম্বর কী?",
        "તમારો મોબાઇલ નંબર શું છે?",
    )

    private fun askDate(label: String) = t(
        "Please say the $label, like 12 March 1990.",
        "$label बताइए, जैसे 12 मार्च 1990।",
        "$label bataiye, jaise 12 March 1990.",
        "$label सांगा, जसे 12 मार्च 1990.",
        "$label சொல்லுங்கள், உதாரணமாக 12 மார்ச் 1990.",
        "$label చెప్పండి, ఉదాహరణకు 12 మార్చి 1990.",
        "$label বলুন, যেমন 12 মার্চ 1990।",
        "$label કહો, જેમ કે 12 માર્ચ 1990.",
    )

    private fun askField(label: String) = t(
        "Please say $label.",
        "$label बताइए।",
        "$label bataiye.",
        "$label सांगा.",
        "$label சொல்லுங்கள்.",
        "$label చెప్పండి.",
        "$label বলুন।",
        "$label કહો.",
    )

    fun askToggle(label: String) = t(
        "Should I select \"$label\"? Say yes or no.",
        "क्या मैं \"$label\" चुनूं? हाँ या नहीं बोलिए।",
        "Kya main \"$label\" select karun? Haan ya nahi boliye.",
        "\"$label\" निवडू का? हो किंवा नाही म्हणा.",
        "\"$label\" தேர்வு செய்யவா? ஆம் அல்லது இல்லை சொல்லுங்கள்.",
        "\"$label\" ఎంచుకోవాలా? అవును లేదా కాదు చెప్పండి.",
        "\"$label\" বেছে নেব? হ্যাঁ বা না বলুন।",
        "\"$label\" પસંદ કરું? હા કે ના કહો.",
    )

    fun askDropdown(label: String) = t(
        "Which option for $label?",
        "$label के लिए कौन सा विकल्प?",
        "$label ke liye kaunsa option?",
        "$label साठी कोणता पर्याय?",
        "$label க்கு எந்த விருப்பம்?",
        "$label కోసం ఏ ఎంపిక?",
        "$label এর জন্য কোন বিকল্প?",
        "$label માટે કયો વિકલ્પ?",
    )

    fun askUseSuggested(question: String, value: String) = t(
        "$question Say yes to use $value.",
        "$question $value के लिए हाँ बोलिए।",
        "$question $value use karne ke liye haan boliye.",
        "$question $value वापरण्यासाठी हो म्हणा.",
        "$question $value பயன்படுத்த ஆம் சொல்லுங்கள்.",
        "$question $value వాడాలంటే అవును చెప్పండి.",
        "$question $value ব্যবহার করতে হ্যাঁ বলুন।",
        "$question $value વાપરવા હા કહો.",
    )

    fun keepExisting(label: String, value: String) = t(
        "$label already has $value. Keep it?",
        "$label में पहले से $value है। रखें?",
        "$label mein pehle se $value hai. Rakhein?",
        "$label मध्ये आधीच $value आहे. ठेवू का?",
        "$label இல் ஏற்கனவே $value உள்ளது. வைத்துக்கொள்ளவா?",
        "$label లో ఇప్పటికే $value ఉంది. ఉంచాలా?",
        "$label এ আগে থেকেই $value আছে। রাখব?",
        "$label માં પહેલેથી $value છે. રાખું?",
    )

    fun sensitiveManual(label: String) = t(
        "For your safety, please type $label yourself. Say next when done.",
        "सुरक्षा के लिए $label खुद टाइप करें। हो जाए तो आगे बोलिए।",
        "Safety ke liye $label khud type kariye. Ho jaaye to next boliye.",
        "सुरक्षेसाठी $label स्वतः टाइप करा. झाल्यावर पुढे म्हणा.",
        "பாதுகாப்புக்காக $label ஐ நீங்களே தட்டச்சு செய்யுங்கள். முடிந்ததும் அடுத்து சொல்லுங்கள்.",
        "భద్రత కోసం $label మీరే టైప్ చేయండి. అయ్యాక తరువాత చెప్పండి.",
        "নিরাপত্তার জন্য $label নিজে টাইপ করুন। হয়ে গেলে পরের বলুন।",
        "સુરક્ષા માટે $label જાતે ટાઇપ કરો. થઈ જાય પછી આગળ કહો.",
    )

    fun filled(value: String) = t(
        "Got it, $value.",
        "ठीक है, $value।",
        "Theek hai, $value.",
        "ठीक आहे, $value.",
        "சரி, $value.",
        "సరే, $value.",
        "ঠিক আছে, $value।",
        "બરાબર, $value.",
    )

    fun filledShort() = t(
        "Done.",
        "हो गया।",
        "Ho gaya.",
        "झाले.",
        "முடிந்தது.",
        "అయింది.",
        "হয়ে গেছে।",
        "થઈ ગયું.",
    )

    fun invalid(message: String) = t(
        "$message. Please try again.",
        "$message। फिर से बोलिए।",
        "$message. Phir se boliye.",
        "$message. पुन्हा सांगा.",
        "$message. மீண்டும் சொல்லுங்கள்.",
        "$message. మళ్ళీ చెప్పండి.",
        "$message। আবার বলুন।",
        "$message. ફરીથી કહો.",
    )

    fun didNotCatch() = t(
        "Sorry, I didn't catch that.",
        "माफ़ कीजिए, समझ नहीं आया।",
        "Sorry, samajh nahi aaya.",
        "माफ करा, समजले नाही.",
        "மன்னிக்கவும், புரியவில்லை.",
        "క్షమించండి, అర్థం కాలేదు.",
        "দুঃখিত, বুঝতে পারিনি।",
        "માફ કરશો, સમજાયું નહીં.",
    )

    /** Low recognition confidence: confirm before typing. */
    fun didYouSay(value: String) = t(
        "Did you say $value?",
        "क्या आपने $value कहा?",
        "Kya aapne $value kaha?",
        "तुम्ही $value म्हणालात का?",
        "நீங்கள் $value என்று சொன்னீர்களா?",
        "మీరు $value అన్నారా?",
        "আপনি কি $value বললেন?",
        "તમે $value કહ્યું?",
    )

    fun pausedNoSpeech() = t(
        "I'll pause now. Tap the mic when you're ready.",
        "मैं रुक रहा हूँ। तैयार हों तो माइक दबाइए।",
        "Main ruk raha hoon. Ready hon to mic dabaiye.",
        "मी थांबतो. तयार झाल्यावर माइक दाबा.",
        "இப்போது நிறுத்துகிறேன். தயாரானதும் மைக்கை அழுத்துங்கள்.",
        "ఇప్పుడు ఆగుతున్నాను. సిద్ధమైనప్పుడు మైక్ నొక్కండి.",
        "এখন থামছি। তৈরি হলে মাইক চাপুন।",
        "હું અટકું છું. તૈયાર હો ત્યારે માઇક દબાવો.",
    )

    fun confirmPress(button: String) = t(
        "All done. Shall I press $button?",
        "सब भर गया। क्या $button दबाऊं?",
        "Sab bhar gaya. Kya $button dabaun?",
        "सगळे भरले. $button दाबू का?",
        "எல்லாம் முடிந்தது. $button அழுத்தவா?",
        "అన్నీ నింపాను. $button నొక్కనా?",
        "সব পূরণ হয়েছে। $button চাপব?",
        "બધું ભરાઈ ગયું. $button દબાવું?",
    )

    private fun whichButtonOf(list: String) = t(
        "Which button should I press? $list.",
        "कौन सा बटन दबाऊं? $list।",
        "Kaunsa button dabaun? $list.",
        "कोणते बटण दाबू? $list.",
        "எந்த பொத்தானை அழுத்த வேண்டும்? $list.",
        "ఏ బటన్ నొక్కాలి? $list.",
        "কোন বোতাম চাপব? $list।",
        "કયું બટન દબાવું? $list.",
    )

    fun nothingToFill() = t(
        "There is nothing to fill here. Say a button name, scroll, or back.",
        "यहाँ भरने को कुछ नहीं है। बटन का नाम, नीचे या वापस बोलिए।",
        "Yahan bharne ko kuch nahi hai. Button ka naam, scroll ya back boliye.",
        "इथे भरण्यासारखे काही नाही. बटणाचे नाव, खाली किंवा मागे म्हणा.",
        "இங்கே நிரப்ப எதுவும் இல்லை. பொத்தானின் பெயர், கீழே அல்லது பின்னால் சொல்லுங்கள்.",
        "ఇక్కడ నింపడానికి ఏమీ లేదు. బటన్ పేరు, కిందకి లేదా వెనక్కి చెప్పండి.",
        "এখানে পূরণ করার কিছু নেই। বোতামের নাম, নিচে বা পিছনে বলুন।",
        "અહીં ભરવા જેવું કંઈ નથી. બટનનું નામ, નીચે કે પાછળ કહો.",
    )

    fun pressed(label: String) = t(
        "Pressed $label.",
        "$label दबा दिया।",
        "$label daba diya.",
        "$label दाबले.",
        "$label அழுத்தப்பட்டது.",
        "$label నొక్కాను.",
        "$label চাপা হয়েছে।",
        "$label દબાવ્યું.",
    )

    fun buttonNotFound(target: String) = t(
        "I couldn't find a button called $target.",
        "$target नाम का बटन नहीं मिला।",
        "$target naam ka button nahi mila.",
        "$target नावाचे बटण सापडले नाही.",
        "$target என்ற பொத்தான் கிடைக்கவில்லை.",
        "$target అనే బటన్ కనబడలేదు.",
        "$target নামে কোনো বোতাম পাইনি।",
        "$target નામનું બટન મળ્યું નહીં.",
    )

    fun actionFailed() = t(
        "That didn't work.",
        "यह नहीं हो पाया।",
        "Yeh nahi ho paya.",
        "ते झाले नाही.",
        "அது வேலை செய்யவில்லை.",
        "అది పని చేయలేదు.",
        "এটা হয়নি।",
        "એ થયું નહીં.",
    )

    fun newScreen() = t(
        "New screen.",
        "नई स्क्रीन।",
        "Nayi screen.",
        "नवीन स्क्रीन.",
        "புதிய திரை.",
        "కొత్త స్క్రీన్.",
        "নতুন স্ক্রিন।",
        "નવી સ્ક્રીન.",
    )

    fun done() = t(
        "All done.",
        "सब हो गया।",
        "Sab ho gaya.",
        "सगळे झाले.",
        "எல்லாம் முடிந்தது.",
        "అంతా అయిపోయింది.",
        "সব হয়ে গেছে।",
        "બધું થઈ ગયું.",
    )

    fun stopped() = t(
        "Stopped.",
        "रोक दिया।",
        "Rok diya.",
        "थांबवले.",
        "நிறுத்தப்பட்டது.",
        "ఆపేశాను.",
        "থামানো হয়েছে।",
        "અટકાવ્યું.",
    )

    fun cannotRead() = t(
        "I can't read this screen.",
        "मैं यह स्क्रीन नहीं पढ़ पा रहा।",
        "Main yeh screen nahi padh pa raha.",
        "मी ही स्क्रीन वाचू शकत नाही.",
        "இந்தத் திரையைப் படிக்க முடியவில்லை.",
        "ఈ స్క్రీన్‌ను చదవలేకపోతున్నాను.",
        "এই স্ক্রিনটি পড়তে পারছি না।",
        "હું આ સ્ક્રીન વાંચી શકતો નથી.",
    )

    fun lookingAtScreen() = t(
        "Let me look at the screen.",
        "मैं स्क्रीन देख रहा हूँ।",
        "Main screen dekh raha hoon.",
        "मी स्क्रीन पाहतो आहे.",
        "திரையைப் பார்க்கிறேன்.",
        "స్క్రీన్‌ను చూస్తున్నాను.",
        "স্ক্রিনটা দেখছি।",
        "હું સ્ક્રીન જોઈ રહ્યો છું.",
    )

    fun serviceOff() = t(
        "Please turn on the VoiceControl accessibility service.",
        "कृपया VoiceControl की सुलभता सेवा चालू करें।",
        "Please VoiceControl accessibility service on kariye.",
        "कृपया VoiceControl ची ॲक्सेसिबिलिटी सेवा सुरू करा.",
        "VoiceControl அணுகல்தன்மை சேவையை இயக்கவும்.",
        "దయచేసి VoiceControl యాక్సెసిబిలిటీ సేవను ఆన్ చేయండి.",
        "অনুগ্রহ করে VoiceControl অ্যাক্সেসিবিলিটি পরিষেবা চালু করুন।",
        "કૃપા કરીને VoiceControl ઍક્સેસિબિલિટી સેવા ચાલુ કરો.",
    )

    fun help() = t(
        "Say the answer, or say next, previous, skip, repeat, submit, scroll, back, or stop.",
        "जवाब बोलिए, या आगे, पिछला, छोड़ो, फिर से, जमा, नीचे, वापस या रुको बोलिए।",
        "Jawab boliye, ya next, pichla, skip, phir se, submit, scroll, back ya stop boliye.",
        "उत्तर सांगा, किंवा पुढे, मागील, वगळा, पुन्हा, जमा करा, खाली, मागे किंवा थांबा म्हणा.",
        "பதிலைச் சொல்லுங்கள், அல்லது அடுத்து, முந்தைய, தவிர், மீண்டும், சமர்ப்பி, கீழே, பின்னால் அல்லது நிறுத்து என்று சொல்லுங்கள்.",
        "సమాధానం చెప్పండి, లేదా తరువాత, మునుపటి, వదిలేయి, మళ్ళీ, సమర్పించు, కిందకి, వెనక్కి లేదా ఆపు అని చెప్పండి.",
        "উত্তর বলুন, অথবা পরের, আগের, বাদ দাও, আবার, জমা দাও, নিচে, পিছনে বা থামো বলুন।",
        "જવાબ કહો, અથવા આગળ, પાછલું, છોડો, ફરીથી, જમા કરો, નીચે, પાછળ અથવા રોકો કહો.",
    )

    fun scrolled() = t(
        "Scrolled.",
        "स्क्रॉल किया।",
        "Scroll kiya.",
        "स्क्रोल केले.",
        "உருட்டப்பட்டது.",
        "స్క్రోల్ చేశాను.",
        "স্ক্রোল করা হয়েছে।",
        "સ્ક્રોલ કર્યું.",
    )

    fun wentBack() = t(
        "Going back.",
        "वापस जा रहे हैं।",
        "Wapas ja rahe hain.",
        "मागे जात आहे.",
        "பின்னால் செல்கிறேன்.",
        "వెనక్కి వెళ్తున్నాను.",
        "পিছনে যাচ্ছি।",
        "પાછળ જઈ રહ્યા છીએ.",
    )

    fun addAnother(item: String) = t(
        "Add another $item?",
        "क्या एक और $item जोड़ें?",
        "Ek aur $item add karein?",
        "आणखी एक $item जोडायचे?",
        "இன்னொரு $item சேர்க்கவா?",
        "మరో $item జోడించాలా?",
        "আরেকটি $item যোগ করব?",
        "બીજું $item ઉમેરું?",
    )

    fun item(item: String, index: Int) = t(
        "$item $index.",
        "$item $index।",
        "$item $index.",
        "$item $index.",
        "$item $index.",
        "$item $index.",
        "$item $index।",
        "$item $index.",
    )

    fun openingApp(app: String) = t(
        "Opening $app.",
        "$app खोल रहे हैं।",
        "$app khol rahe hain.",
        "$app उघडत आहे.",
        "$app திறக்கிறேன்.",
        "$app తెరుస్తున్నాను.",
        "$app খুলছি।",
        "$app ખોલી રહ્યા છીએ.",
    )

    fun waitingForScreen() = t(
        "Waiting for the next screen.",
        "अगली स्क्रीन का इंतज़ार कर रहे हैं।",
        "Agli screen ka intezaar kar rahe hain.",
        "पुढच्या स्क्रीनची वाट पाहत आहे.",
        "அடுத்த திரைக்காக காத்திருக்கிறேன்.",
        "తదుపరి స్క్రీన్ కోసం ఎదురుచూస్తున్నాను.",
        "পরের স্ক্রিনের জন্য অপেক্ষা করছি।",
        "આગળની સ્ક્રીનની રાહ જોઈ રહ્યા છીએ.",
    )

    fun screenNotReached() = t(
        "The next screen didn't open, so I stopped the flow.",
        "अगली स्क्रीन नहीं खुली, इसलिए फ़्लो रोक दिया।",
        "Agli screen nahi khuli, isliye flow rok diya.",
        "पुढची स्क्रीन उघडली नाही, म्हणून फ्लो थांबवला.",
        "அடுத்த திரை திறக்கவில்லை, அதனால் நிறுத்தினேன்.",
        "తదుపరి స్క్రీన్ తెరుచుకోలేదు, అందుకే ఆపేశాను.",
        "পরের স্ক্রিন খোলেনি, তাই থামিয়ে দিলাম।",
        "આગળની સ્ક્રીન ખૂલી નહીં, એટલે અટકાવ્યું.",
    )

    fun cleared(label: String) = t(
        "Cleared $label.",
        "$label मिटा दिया।",
        "$label mita diya.",
        "$label पुसले.",
        "$label அழிக்கப்பட்டது.",
        "$label తుడిచేశాను.",
        "$label মুছে ফেলা হয়েছে।",
        "$label ભૂંસી નાખ્યું.",
    )
}
