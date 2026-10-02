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

    fun describeToggle(label: String, checked: Boolean) = if (checked) describeChecked(label) else describeUnchecked(label)


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

    fun askUseLastTime(question: String, value: String) = t(
        "$question Last time you said $value. Say yes to use it again.",
        "$question पिछली बार आपने $value कहा था। फिर से वही रखने के लिए हाँ बोलिए।",
        "$question Pichhli baar aapne $value kaha tha. Wahi rakhne ke liye haan boliye.",
        "$question मागच्या वेळी तुम्ही $value म्हणालात. तेच वापरण्यासाठी हो म्हणा.",
        "$question கடந்த முறை $value என்றீர்கள். மீண்டும் பயன்படுத்த ஆம் சொல்லுங்கள்.",
        "$question గతసారి మీరు $value అన్నారు. మళ్ళీ వాడాలంటే అవును చెప్పండి.",
        "$question আগের বার আপনি $value বলেছিলেন। আবার ব্যবহার করতে হ্যাঁ বলুন।",
        "$question ગયા વખતે તમે $value કહ્યું હતું. ફરી વાપરવા હા કહો.",
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

    /** First question on a screen without a form: the assistant offers help instead of listing buttons. */
    fun howCanIHelp() = t(
        "What can I do for you?",
        "बताइए, मैं आपके लिए क्या करूं?",
        "Bataiye, main aapke liye kya karun?",
        "सांगा, मी तुमच्यासाठी काय करू?",
        "சொல்லுங்கள், உங்களுக்கு என்ன செய்யட்டும்?",
        "చెప్పండి, మీ కోసం ఏమి చేయాలి?",
        "বলুন, আপনার জন্য কী করব?",
        "કહો, હું તમારા માટે શું કરું?",
    )

    /** After an action, on the next screen without a form. */
    fun whatNext() = t(
        "What next?",
        "अब आगे क्या करना है?",
        "Ab aage kya karna hai?",
        "आता पुढे काय करायचे?",
        "அடுத்து என்ன செய்யலாம்?",
        "తరువాత ఏమి చేయాలి?",
        "এরপর কী করব?",
        "હવે આગળ શું કરવું છે?",
    )

    /** Suggestions when the user is unsure: what this screen offers, and that apps can be opened. */
    fun suggest(options: List<String>): String {
        if (options.isEmpty()) return suggestApps()
        val list = options.joinToString(", ")
        return t(
            "Here you can: $list. Just say one, or say open and an app name.",
            "यहाँ आप ये कर सकते हैं: $list। इनमें से कोई एक बोलिए, या ऐप का नाम लेकर खोलो बोलिए।",
            "Yahan aap ye kar sakte hain: $list. Inmein se koi ek boliye, ya app ka naam lekar kholo boliye.",
            "इथे तुम्ही हे करू शकता: $list. यापैकी एक सांगा, किंवा अॅपचे नाव घेऊन उघडा म्हणा.",
            "இங்கே நீங்கள் செய்யலாம்: $list. ஒன்றைச் சொல்லுங்கள், அல்லது ஆப் பெயருடன் திற என்று சொல்லுங்கள்.",
            "ఇక్కడ మీరు చేయగలరు: $list. వీటిలో ఒకటి చెప్పండి, లేదా యాప్ పేరుతో తెరువు అని చెప్పండి.",
            "এখানে আপনি করতে পারেন: $list। যেকোনো একটা বলুন, অথবা অ্যাপের নাম বলে খোলো বলুন।",
            "અહીં તમે આ કરી શકો: $list. એમાંથી એક કહો, અથવા એપનું નામ લઈને ખોલો કહો.",
        )
    }

    private fun suggestApps() = t(
        "You can say open and an app name, like open WhatsApp, or say scroll, back or stop.",
        "आप ऐप का नाम लेकर खोलो बोल सकते हैं, जैसे व्हाट्सऐप खोलो, या नीचे, वापस या रुको बोलिए।",
        "Aap app ka naam lekar kholo bol sakte hain, jaise WhatsApp kholo, ya scroll, back ya stop boliye.",
        "तुम्ही अॅपचे नाव घेऊन उघडा म्हणू शकता, जसे व्हॉट्सअॅप उघडा, किंवा खाली, मागे किंवा थांबा म्हणा.",
        "ஆப் பெயருடன் திற என்று சொல்லலாம், உதாரணமாக வாட்ஸ்அப் திற, அல்லது கீழே, பின்னால், நிறுத்து என்று சொல்லுங்கள்.",
        "యాప్ పేరుతో తెరువు అని చెప్పవచ్చు, ఉదాహరణకు వాట్సాప్ తెరువు, లేదా కిందకి, వెనక్కి లేదా ఆపు అని చెప్పండి.",
        "অ্যাপের নাম বলে খোলো বলতে পারেন, যেমন হোয়াটসঅ্যাপ খোলো, অথবা নিচে, পিছনে বা থামো বলুন।",
        "એપનું નામ લઈને ખોલો કહી શકો, જેમ કે વોટ્સએપ ખોલો, અથવા નીચે, પાછળ કે રોકો કહો.",
    )

    fun opening(app: String) = t(
        "Opening $app.",
        "$app खोल रहा हूं।",
        "$app khol raha hoon.",
        "$app उघडत आहे.",
        "$app திறக்கிறேன்.",
        "$app తెరుస్తున్నాను.",
        "$app খুলছি।",
        "$app ખોલું છું.",
    )

    fun appNotFound(name: String) = t(
        "I couldn't find an app called $name on this phone.",
        "इस फ़ोन पर $name नाम का ऐप नहीं मिला।",
        "Is phone par $name naam ka app nahi mila.",
        "या फोनवर $name नावाचे अॅप सापडले नाही.",
        "இந்த போனில் $name என்ற ஆப் இல்லை.",
        "ఈ ఫోన్‌లో $name అనే యాప్ లేదు.",
        "এই ফোনে $name নামের অ্যাপ পাইনি।",
        "આ ફોનમાં $name નામની એપ મળી નહીં.",
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

    /** Screen reader: how many items and how to move between them. */
    fun readerStart(count: Int) = t(
        "This screen has $count items. Say next, previous, select, read all, or stop.",
        "इस स्क्रीन पर $count चीज़ें हैं। आगे, पिछला, चुनो, सब पढ़ो या रुको बोलिए।",
        "Is screen par $count cheezein hain. Next, pichla, select, sab padho ya stop boliye.",
        "या स्क्रीनवर $count गोष्टी आहेत. पुढे, मागील, निवडा, सर्व वाचा किंवा थांबा म्हणा.",
        "இந்தத் திரையில் $count உருப்படிகள் உள்ளன. அடுத்து, முந்தைய, தேர்வு, அனைத்தையும் படி அல்லது நிறுத்து என்று சொல்லுங்கள்.",
        "ఈ స్క్రీన్‌లో $count అంశాలు ఉన్నాయి. తరువాత, మునుపటి, ఎంచుకో, అన్నీ చదువు లేదా ఆపు అని చెప్పండి.",
        "এই স্ক্রিনে ${count}টি জিনিস আছে। পরের, আগের, বেছে নাও, সব পড়ো বা থামো বলুন।",
        "આ સ્ક્રીન પર $count વસ્તુઓ છે. આગળ, પાછલું, પસંદ કરો, બધું વાંચો અથવા રોકો કહો.",
    )

    fun readerEnd() = t(
        "End of screen.",
        "स्क्रीन खत्म।",
        "Screen khatam.",
        "स्क्रीन संपली.",
        "திரை முடிந்தது.",
        "స్క్రీన్ ముగిసింది.",
        "স্ক্রিন শেষ।",
        "સ્ક્રીન પૂરી.",
    )

    fun describeButton(label: String) = t(
        "Button, $label.",
        "बटन, $label।",
        "Button, $label.",
        "बटण, $label.",
        "பொத்தான், $label.",
        "బటన్, $label.",
        "বোতাম, $label।",
        "બટન, $label.",
    )

    fun describeFilled(label: String, value: String) = t(
        "$label field, contains $value.",
        "$label खाना, इसमें $value है।",
        "$label field, ismein $value hai.",
        "$label रकाना, यात $value आहे.",
        "$label புலம், இதில் $value உள்ளது.",
        "$label ఖాళీ, ఇందులో $value ఉంది.",
        "$label ঘর, এতে $value আছে।",
        "$label ખાનું, એમાં $value છે.",
    )

    fun describeEmpty(label: String) = t(
        "$label field, empty.",
        "$label खाना, खाली।",
        "$label field, khaali.",
        "$label रकाना, रिकामा.",
        "$label புலம், காலியாக உள்ளது.",
        "$label ఖాళీ, ఏమీ లేదు.",
        "$label ঘর, খালি।",
        "$label ખાનું, ખાલી.",
    )

    fun describePrivate(label: String) = t(
        "$label field, private.",
        "$label खाना, निजी।",
        "$label field, private.",
        "$label रकाना, खाजगी.",
        "$label புலம், தனிப்பட்டது.",
        "$label ఖాళీ, ప్రైవేట్.",
        "$label ঘর, ব্যক্তিগত।",
        "$label ખાનું, ખાનગી.",
    )

    private fun describeChecked(label: String) = t(
        "$label, checked.",
        "$label, चुना हुआ।",
        "$label, selected.",
        "$label, निवडलेले.",
        "$label, தேர்ந்தெடுக்கப்பட்டது.",
        "$label, ఎంచుకోబడింది.",
        "$label, বেছে নেওয়া।",
        "$label, પસંદ કરેલું.",
    )

    private fun describeUnchecked(label: String) = t(
        "$label, not checked.",
        "$label, नहीं चुना।",
        "$label, select nahi.",
        "$label, निवडलेले नाही.",
        "$label, தேர்ந்தெடுக்கப்படவில்லை.",
        "$label, ఎంచుకోలేదు.",
        "$label, বেছে নেওয়া হয়নি।",
        "$label, પસંદ નથી.",
    )

    fun undone(label: String) = t(
        "Undid $label.",
        "$label पहले जैसा कर दिया।",
        "$label undo kar diya.",
        "$label पूर्ववत केले.",
        "$label மாற்றம் திரும்பப் பெறப்பட்டது.",
        "$label మార్పు రద్దు చేశాను.",
        "$label আগের মতো করা হয়েছে।",
        "$label પૂર્વવત્ કર્યું.",
    )

    fun nothingToUndo() = t(
        "There is nothing to undo.",
        "वापस लेने को कुछ नहीं है।",
        "Undo karne ko kuch nahi hai.",
        "पूर्ववत करण्यासारखे काही नाही.",
        "திரும்பப் பெற எதுவும் இல்லை.",
        "రద్దు చేయడానికి ఏమీ లేదు.",
        "ফেরানোর মতো কিছু নেই।",
        "પૂર્વવત્ કરવા જેવું કંઈ નથી.",
    )

    /** Guard before a button that can't be undone (pay, delete, …). */
    fun confirmDestructive(label: String) = t(
        "$label can't be undone. Are you sure?",
        "$label वापस नहीं हो सकता। पक्का?",
        "$label wapas nahi ho sakta. Pakka?",
        "$label परत करता येणार नाही. नक्की?",
        "$label ஐத் திரும்பப் பெற முடியாது. உறுதியா?",
        "$label వెనక్కి తీసుకోలేము. ఖచ్చితంగానా?",
        "$label ফেরানো যাবে না। নিশ্চিত?",
        "$label પાછું નહીં થાય. ચોક્કસ?",
    )

    fun notPressed(label: String) = t(
        "Okay, I didn't press $label.",
        "ठीक है, $label नहीं दबाया।",
        "Theek hai, $label nahi dabaya.",
        "ठीक आहे, $label दाबले नाही.",
        "சரி, $label அழுத்தவில்லை.",
        "సరే, $label నొక్కలేదు.",
        "ঠিক আছে, $label চাপিনি।",
        "બરાબર, $label દબાવ્યું નહીં.",
    )

    fun readerHelp() = t(
        "Say next or previous, next button or next field, top or bottom, select, read all or read everything, where am I, find and a word, faster or slower, undo, or stop.",
        "अगला या पिछला, अगला बटन या अगला खाना, सबसे ऊपर या सबसे नीचे, चुनो, सब पढ़ो या पूरा पढ़ो, मैं कहाँ हूँ, कोई शब्द और ढूंढो, तेज़ या धीरे, वापस करो, या रुको बोलिए।",
        "Agla ya pichhla, agla button ya agla khana, sabse upar ya sabse neeche, select, sab padho ya poora padho, main kahan hoon, koi shabd aur dhundo, tez ya dheere, undo, ya ruko boliye.",
        "पुढचा किंवा मागचा, पुढचे बटण किंवा पुढचा रकाना, सबसे वर किंवा शेवटचा, निवडा, सगळे वाचा, मी कुठे आहे, शब्द आणि शोधा, वेगाने किंवा हळू बोला, किंवा थांबा म्हणा.",
        "அடுத்து அல்லது முந்தையது, next button அல்லது next field, top அல்லது bottom, தேர்ந்தெடு, எல்லாம் படி, where am I, find மற்றும் ஒரு சொல், faster அல்லது slower, அல்லது நிறுத்து என்று சொல்லுங்கள்.",
        "తదుపరి లేదా మునుపటి, next button లేదా next field, top లేదా bottom, ఎంచుకో, అన్నీ చదువు, where am I, find మరియు ఒక పదం, faster లేదా slower, లేదా ఆపు అని చెప్పండి.",
        "পরের বা আগের, next button বা next field, top বা bottom, বেছে নিন, সব পড়ো, where am I, find আর একটি শব্দ, faster বা slower, বা থামো বলুন।",
        "આગળ કે પાછળ, next button કે next field, top કે bottom, પસંદ કરો, બધું વાંચો, where am I, find અને એક શબ્દ, faster કે slower, અથવા રોકો કહો.",
    )

    fun noMoreOfThat() = t(
        "There are no more of those.",
        "इसके आगे ऐसा कुछ नहीं है।",
        "Iske aage aisa kuch nahi hai.",
        "यापुढे असे काही नाही.",
        "இதற்கு மேல் அப்படி எதுவும் இல்லை.",
        "ఇంకా అలాంటివి లేవు.",
        "এরকম আর কিছু নেই।",
        "આવું બીજું કંઈ નથી.",
    )

    fun whereAmI(screen: String, position: Int, total: Int, fields: Int, empty: Int, buttons: Int) = t(
        "$screen. Item $position of $total. $fields fields, $empty empty, and $buttons buttons.",
        "$screen। $total में से $position। $fields खाने, $empty खाली, और $buttons बटन।",
        "$screen. $total mein se $position. $fields fields, $empty khaali, aur $buttons buttons.",
        "$screen. $total पैकी $position. $fields रकाने, $empty रिकामे, आणि $buttons बटणे.",
        "$screen. $total இல் $position. $fields புலங்கள், $empty காலி, $buttons பொத்தான்கள்.",
        "$screen. $total లో $position. $fields ఖాళీలు, $empty ఖాళీగా, $buttons బటన్లు.",
        "$screen। $total এর মধ্যে $position। $fields ঘর, $empty খালি, আর $buttons বোতাম।",
        "$screen. $total માંથી $position. $fields ખાના, $empty ખાલી, અને $buttons બટન.",
    )

    fun notFoundOnScreen(query: String) = t(
        "I couldn't find $query on this screen.",
        "इस स्क्रीन पर $query नहीं मिला।",
        "Is screen par $query nahi mila.",
        "या स्क्रीनवर $query सापडले नाही.",
        "இந்தத் திரையில் $query கிடைக்கவில்லை.",
        "ఈ స్క్రీన్‌పై $query కనిపించలేదు.",
        "এই স্ক্রিনে $query পাওয়া যায়নি।",
        "આ સ્ક્રીન પર $query મળ્યું નહીં.",
    )

    fun rateChanged(faster: Boolean) = if (faster) {
        t("Speaking faster.", "अब तेज़ बोलूँगा।", "Ab tez bolunga.", "आता वेगाने बोलतो.", "வேகமாகப் பேசுகிறேன்.", "వేగంగా మాట్లాడుతాను.", "এখন দ্রুত বলব।", "હવે ઝડપથી બોલીશ.")
    } else {
        t("Speaking slower.", "अब धीरे बोलूँगा।", "Ab dheere bolunga.", "आता हळू बोलतो.", "மெதுவாகப் பேசுகிறேன்.", "నెమ్మదిగా మాట్లాడుతాను.", "এখন ধীরে বলব।", "હવે ધીમે બોલીશ.")
    }

    fun offlinePackMissing(language: String) = t(
        "Speech for $language isn't downloaded for offline use. Connect to the internet, or download it in Settings, Offline languages.",
        "$language की आवाज़ ऑफ़लाइन के लिए डाउनलोड नहीं है। इंटरनेट चालू करें, या सेटिंग्स में ऑफ़लाइन भाषाएँ से डाउनलोड करें।",
        "$language ki awaaz offline ke liye download nahi hai. Internet chalu karein, ya Settings mein Offline languages se download karein.",
        "$language साठी ऑफलाइन आवाज डाउनलोड केलेली नाही. इंटरनेट चालू करा, किंवा सेटिंग्जमधील ऑफलाइन भाषा मधून डाउनलोड करा.",
        "$language ஆஃப்லைன் குரல் பதிவிறக்கப்படவில்லை. இணையத்தை இயக்குங்கள், அல்லது அமைப்புகளில் ஆஃப்லைன் மொழிகளில் பதிவிறக்குங்கள்.",
        "$language ఆఫ్‌లైన్ వాయిస్ డౌన్‌లోడ్ కాలేదు. ఇంటర్నెట్ ఆన్ చేయండి, లేదా సెట్టింగ్స్‌లో ఆఫ్‌లైన్ భాషలు నుండి డౌన్‌లోడ్ చేయండి.",
        "$language অফলাইন ভয়েস ডাউনলোড করা নেই। ইন্টারনেট চালু করুন, বা সেটিংসে অফলাইন ভাষা থেকে ডাউনলোড করুন।",
        "$language ઑફલાઇન અવાજ ડાઉનલોડ નથી. ઇન્ટરનેટ ચાલુ કરો, અથવા સેટિંગ્સમાં ઑફલાઇન ભાષાઓમાંથી ડાઉનલોડ કરો.",
    )

    fun startingShortcut(flowName: String) = t(
        "Starting $flowName.",
        "$flowName शुरू कर रहा हूँ।",
        "$flowName shuru kar raha hoon.",
        "$flowName सुरू करत आहे.",
        "$flowName தொடங்குகிறேன்.",
        "$flowName ప్రారంభిస్తున్నాను.",
        "$flowName শুরু করছি।",
        "$flowName શરૂ કરું છું.",
    )

    fun shortcutUnavailable(phrase: String) = t(
        "The flow for \"$phrase\" is not on this phone yet. Connect to the internet and try again.",
        "\"$phrase\" वाला फ्लो अभी इस फ़ोन पर नहीं है। इंटरनेट चालू करके फिर कोशिश करें।",
        "\"$phrase\" wala flow abhi is phone par nahi hai. Internet chalu karke phir koshish karein.",
        "\"$phrase\" चा फ्लो अजून या फोनवर नाही. इंटरनेट चालू करून पुन्हा प्रयत्न करा.",
        "\"$phrase\" க்கான செயல்முறை இந்த போனில் இல்லை. இணையத்தை இயக்கி மீண்டும் முயற்சிக்கவும்.",
        "\"$phrase\" కోసం ఫ్లో ఈ ఫోన్‌లో ఇంకా లేదు. ఇంటర్నెట్ ఆన్ చేసి మళ్ళీ ప్రయత్నించండి.",
        "\"$phrase\" এর ফ্লো এখনও এই ফোনে নেই। ইন্টারনেট চালু করে আবার চেষ্টা করুন।",
        "\"$phrase\" માટેનો ફ્લો હજી આ ફોન પર નથી. ઇન્ટરનેટ ચાલુ કરીને ફરી પ્રયાસ કરો.",
    )

    // --- Personal assistant ---------------------------------------------------------------------

    fun timeNow(time: String) = t(
        "It's $time.",
        "अभी $time हुए हैं।",
        "Abhi $time hue hain.",
        "आता $time वाजले आहेत.",
        "இப்போது மணி $time.",
        "ఇప్పుడు సమయం $time.",
        "এখন $time বাজে।",
        "અત્યારે $time થયા છે.",
    )

    fun today(date: String) = t(
        "Today is $date.",
        "आज $date है।",
        "Aaj $date hai.",
        "आज $date आहे.",
        "இன்று $date.",
        "ఈ రోజు $date.",
        "আজ $date।",
        "આજે $date છે.",
    )

    fun alarmSet(time: String) = t(
        "Done. Your alarm is set for $time.",
        "ठीक है, $time का अलार्म लगा दिया।",
        "Theek hai, $time ka alarm laga diya.",
        "ठीक आहे, $time चा अलार्म लावला.",
        "சரி, $time க்கு அலாரம் வைத்துவிட்டேன்.",
        "సరే, $time కి అలారం పెట్టాను.",
        "ঠিক আছে, $time এ অ্যালার্ম দিয়ে দিলাম।",
        "બરાબર, $time નો એલાર્મ મૂકી દીધો.",
    )

    fun timerSet(duration: String) = t(
        "Okay, timer started for $duration.",
        "ठीक है, $duration का टाइमर शुरू कर दिया।",
        "Theek hai, $duration ka timer shuru kar diya.",
        "ठीक आहे, $duration चा टायमर सुरू केला.",
        "சரி, $duration டைமர் தொடங்கிவிட்டேன்.",
        "సరే, $duration టైమర్ మొదలుపెట్టాను.",
        "ঠিক আছে, $duration এর টাইমার চালু করলাম।",
        "બરાબર, $duration નું ટાઇમર ચાલુ કર્યું.",
    )

    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val sec = seconds % 60
        fun part(n: Int, en: String, hi: String, hinglish: String, mr: String, ta: String, te: String, bn: String, gu: String) =
            if (n == 0) null else "$n " + t(en, hi, hinglish, mr, ta, te, bn, gu)
        return listOfNotNull(
            part(h, if (h == 1) "hour" else "hours", "घंटे", "ghante", "तास", "மணி நேரம்", "గంటలు", "ঘণ্টা", "કલાક"),
            part(m, if (m == 1) "minute" else "minutes", "मिनट", "minute", "मिनिटे", "நிமிடம்", "నిమిషాలు", "মিনিট", "મિનિટ"),
            part(sec, if (sec == 1) "second" else "seconds", "सेकंड", "second", "सेकंद", "வினாடி", "సెకన్లు", "সেকেন্ড", "સેકન્ડ"),
        ).joinToString(" ")
    }

    fun searching(query: String) = t(
        "Searching for $query.",
        "$query ढूंढ रहा हूं।",
        "$query dhoondh raha hoon.",
        "$query शोधत आहे.",
        "$query தேடுகிறேன்.",
        "$query వెతుకుతున్నాను.",
        "$query খুঁজছি।",
        "$query શોધું છું.",
    )

    fun calling(name: String) = t(
        "Calling $name.",
        "$name को कॉल लगा रहा हूं।",
        "$name ko call laga raha hoon.",
        "$name ला कॉल करत आहे.",
        "$name க்கு அழைக்கிறேன்.",
        "$name కి కాల్ చేస్తున్నాను.",
        "$name কে কল করছি।",
        "$name ને કૉલ કરું છું.",
    )

    fun dialed(name: String) = t(
        "$name's number is ready. Say call and I'll call.",
        "$name का नंबर लगा दिया है। कॉल बोलिए तो कॉल कर दूंगा।",
        "$name ka number laga diya hai. Call boliye to call kar dunga.",
        "$name चा नंबर लावला आहे. कॉल म्हणा, मी कॉल करतो.",
        "$name எண் தயார். கால் என்று சொன்னால் அழைக்கிறேன்.",
        "$name నంబర్ సిద్ధం. కాల్ అని చెప్పండి, కాల్ చేస్తాను.",
        "$name এর নম্বর তৈরি। কল বললে কল করব।",
        "$name નો નંબર તૈયાર છે. કૉલ કહો તો કૉલ કરી દઉં.",
    )

    fun askMessage(name: String) = t(
        "What should I write to $name?",
        "$name को क्या लिखूं?",
        "$name ko kya likhun?",
        "$name ला काय लिहू?",
        "$name க்கு என்ன எழுதட்டும்?",
        "$name కి ఏమి రాయాలి?",
        "$name কে কী লিখব?",
        "$name ને શું લખું?",
    )

    fun messageReady(name: String) = t(
        "Your message to $name is ready. Shall I send it?",
        "$name के लिए मैसेज तैयार है। भेज दूं?",
        "$name ke liye message taiyaar hai. Bhej doon?",
        "$name साठी मेसेज तयार आहे. पाठवू का?",
        "$name க்கு செய்தி தயார். அனுப்பட்டுமா?",
        "$name కి మెసేజ్ సిద్ధం. పంపనా?",
        "$name এর জন্য মেসেজ তৈরি। পাঠিয়ে দেব?",
        "$name માટે મેસેજ તૈયાર છે. મોકલી દઉં?",
    )

    fun messageSent(name: String) = t(
        "Sent to $name.",
        "$name को भेज दिया।",
        "$name ko bhej diya.",
        "$name ला पाठवला.",
        "$name க்கு அனுப்பிவிட்டேன்.",
        "$name కి పంపాను.",
        "$name কে পাঠিয়ে দিলাম।",
        "$name ને મોકલી દીધો.",
    )

    fun contactNotFound(name: String) = t(
        "I couldn't find $name in your contacts.",
        "आपके कॉन्टैक्ट्स में $name नहीं मिला।",
        "Aapke contacts mein $name nahi mila.",
        "तुमच्या संपर्कांमध्ये $name सापडले नाही.",
        "உங்கள் தொடர்புகளில் $name இல்லை.",
        "మీ కాంటాక్ట్స్‌లో $name లేరు.",
        "আপনার কন্টাক্টে $name পাইনি।",
        "તમારા કૉન્ટેક્ટ્સમાં $name મળ્યા નહીં.",
    )

    fun needContacts() = t(
        "I need permission to see your contacts and make calls. Please tap Allow on the screen, then ask me again.",
        "कॉन्टैक्ट्स देखने और कॉल करने की अनुमति चाहिए। स्क्रीन पर अनुमति दें दबाइए, फिर दोबारा बोलिए।",
        "Contacts dekhne aur call karne ki permission chahiye. Screen par Allow dabaiye, phir dobara boliye.",
        "संपर्क पाहण्याची आणि कॉल करण्याची परवानगी हवी आहे. स्क्रीनवर परवानगी द्या, मग पुन्हा सांगा.",
        "தொடர்புகளைப் பார்க்கவும் அழைக்கவும் அனுமதி தேவை. திரையில் அனுமதி கொடுத்து மீண்டும் சொல்லுங்கள்.",
        "కాంటాక్ట్స్ చూడటానికి, కాల్ చేయడానికి అనుమతి కావాలి. స్క్రీన్‌పై అనుమతించి మళ్ళీ చెప్పండి.",
        "কন্টাক্ট দেখা আর কল করার অনুমতি দরকার। স্ক্রিনে অনুমতি দিন, তারপর আবার বলুন।",
        "કૉન્ટેક્ટ્સ જોવા અને કૉલ કરવાની પરવાનગી જોઈએ. સ્ક્રીન પર મંજૂરી આપો, પછી ફરી કહો.",
    )

    fun taskFailed() = t(
        "Sorry, I couldn't do that on this phone.",
        "माफ़ कीजिए, इस फ़ोन पर ये नहीं हो पाया।",
        "Maaf kijiye, is phone par ye nahi ho paaya.",
        "माफ करा, या फोनवर हे झाले नाही.",
        "மன்னிக்கவும், இந்த போனில் இதைச் செய்ய முடியவில்லை.",
        "క్షమించండి, ఈ ఫోన్‌లో ఇది కుదరలేదు.",
        "দুঃখিত, এই ফোনে এটা করা গেল না।",
        "માફ કરશો, આ ફોન પર આ થઈ શક્યું નહીં.",
    )

    /** Follow-up after an answer, worded differently each time so it sounds like a person. */
    fun anythingElse(turn: Int): String = when (turn % 3) {
        0 -> t("Anything else?", "और कुछ?", "Aur kuch?", "आणखी काही?", "வேறு ஏதாவது?", "ఇంకేమైనా?", "আর কিছু?", "બીજું કંઈ?")
        1 -> t(
            "What else can I do for you?", "और क्या कर दूं आपके लिए?", "Aur kya kar doon aapke liye?", "आणखी काय करू तुमच्यासाठी?",
            "வேறு என்ன செய்யட்டும்?", "ఇంకా ఏమి చేయాలి?", "আর কী করে দেব?", "બીજું શું કરી આપું?",
        )
        else -> t(
            "Tell me if you need anything else.", "कुछ और चाहिए तो बताइए।", "Kuch aur chahiye to bataiye.", "आणखी काही हवे असल्यास सांगा.",
            "வேறு ஏதாவது வேண்டுமானால் சொல்லுங்கள்.", "ఇంకేమైనా కావాలంటే చెప్పండి.", "আর কিছু লাগলে বলুন।", "બીજું કંઈ જોઈએ તો કહો.",
        )
    }

    // --- Helper that operates the app ("do it for me") --------------------------------------------

    fun onIt() = t(
        "Sure, I'll do it. I'll ask you if I need anything.",
        "ठीक है, मैं कर देता हूं। कुछ चाहिए होगा तो आपसे पूछ लूंगा।",
        "Theek hai, main kar deta hoon. Kuch chahiye hoga to aapse pooch lunga.",
        "ठीक आहे, मी करतो. काही लागले तर तुम्हाला विचारेन.",
        "சரி, நான் செய்கிறேன். ஏதாவது தேவைப்பட்டால் கேட்கிறேன்.",
        "సరే, నేను చేస్తాను. ఏదైనా కావాలంటే మిమ్మల్ని అడుగుతాను.",
        "ঠিক আছে, আমি করে দিচ্ছি। কিছু লাগলে জিজ্ঞেস করব।",
        "બરાબર, હું કરી દઉં છું. કંઈ જોઈએ તો તમને પૂછીશ.",
    )

    fun goalDone() = t(
        "Done.", "हो गया।", "Ho gaya.", "झाले.", "முடிந்தது.", "అయిపోయింది.", "হয়ে গেছে।", "થઈ ગયું.",
    )

    fun goalFailed() = t(
        "Sorry, I couldn't finish that here. You can tell me another way, or say stop.",
        "माफ़ कीजिए, ये यहाँ पूरा नहीं हो पाया। आप कोई और तरीका बताइए, या रुको बोलिए।",
        "Maaf kijiye, ye yahan poora nahi ho paaya. Aap koi aur tareeka bataiye, ya stop boliye.",
        "माफ करा, हे इथे पूर्ण झाले नाही. दुसरा मार्ग सांगा, किंवा थांबा म्हणा.",
        "மன்னிக்கவும், இதை இங்கே முடிக்க முடியவில்லை. வேறு வழி சொல்லுங்கள், அல்லது நிறுத்து என்று சொல்லுங்கள்.",
        "క్షమించండి, ఇది ఇక్కడ పూర్తి కాలేదు. వేరే మార్గం చెప్పండి, లేదా ఆపు అని చెప్పండి.",
        "দুঃখিত, এটা এখানে শেষ করা গেল না। অন্য উপায় বলুন, অথবা থামো বলুন।",
        "માફ કરશો, આ અહીં પૂરું ન થયું. બીજો રસ્તો કહો, અથવા રોકો કહો.",
    )

    fun agentStuck(options: List<String>): String {
        val list = options.joinToString(", ")
        return t(
            "I'm not sure what to press here. Which one should I press? $list.",
            "मुझे यहाँ समझ नहीं आ रहा कौन सा दबाऊं। आप बताइए: $list।",
            "Mujhe yahan samajh nahi aa raha kaunsa dabaun. Aap bataiye: $list.",
            "इथे काय दाबायचे ते कळत नाही. तुम्ही सांगा: $list.",
            "இங்கே எதை அழுத்துவது என்று தெரியவில்லை. நீங்கள் சொல்லுங்கள்: $list.",
            "ఇక్కడ ఏది నొక్కాలో తెలియడం లేదు. మీరు చెప్పండి: $list.",
            "এখানে কোনটা টিপব বুঝতে পারছি না। আপনি বলুন: $list।",
            "અહીં શું દબાવવું સમજાતું નથી. તમે કહો: $list.",
        )
    }

    fun needInternetForHelp() = t(
        "To do this for you I need the internet and to be signed in. Meanwhile, tell me which button to press.",
        "ये आपके लिए करने के लिए इंटरनेट और साइन इन चाहिए। तब तक बताइए कौन सा बटन दबाऊं।",
        "Ye aapke liye karne ke liye internet aur sign in chahiye. Tab tak bataiye kaunsa button dabaun.",
        "हे तुमच्यासाठी करायला इंटरनेट आणि साइन इन हवे. तोपर्यंत कोणते बटण दाबू ते सांगा.",
        "இதைச் செய்ய இணையமும் உள்நுழைவும் தேவை. அதுவரை எந்த பட்டனை அழுத்த வேண்டும் என்று சொல்லுங்கள்.",
        "ఇది చేయడానికి ఇంటర్నెట్, సైన్ ఇన్ కావాలి. అప్పటివరకు ఏ బటన్ నొక్కాలో చెప్పండి.",
        "এটা করতে ইন্টারনেট আর সাইন ইন লাগবে। ততক্ষণ বলুন কোন বোতাম টিপব।",
        "આ કરવા ઇન્ટરનેટ અને સાઇન ઇન જોઈએ. ત્યાં સુધી કયું બટન દબાવું તે કહો.",
    )
}
