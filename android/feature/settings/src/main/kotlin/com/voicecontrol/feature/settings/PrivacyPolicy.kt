package com.voicecontrol.feature.settings

/** In-app copy of docs/privacy-policy.md (kept short; the full policy is published with the app). */
object PrivacyPolicy {
    val sections: List<Pair<String, String>> = listOf(
        "What VoiceControl reads" to
            "When the accessibility service is on, VoiceControl reads the labels, types and current values of input fields and buttons on the app in front of you, only to ask you questions and fill fields you answer. It never reads, speaks, stores or sends the contents of password, OTP, PIN or CVV fields.",
        "Microphone" to
            "Audio is captured only while a voice session is running (you see the red mic and a \"VoiceControl is listening\" notification). Speech is converted to text by your phone's speech recognition service. VoiceControl does not record or keep audio.",
        "What is sent to the server" to
            "If you sign in and do not use local-only mode, the field labels of the current screen and the text of what you said are sent to the VoiceControl server to understand your answer, and screen structure is used to find your saved flows. Values of sensitive fields are never sent. With the vision fallback turned on, a screenshot is sent only for apps that expose no readable fields.",
        "AI providers" to
            "The server may use an AI provider (for example Anthropic or OpenAI) to interpret speech. Your phone never contacts them directly. Providers process requests under their API terms and do not use them for advertising.",
        "What is stored" to
            "Saved flows store field labels, questions and your edits, never the values you typed unless you add a default yourself. History stores what happened to each field (filled, skipped…) without the values. Your profile stores only the details you enter.",
        "Your controls" to
            "Turn on local-only mode to keep everything on the phone. Turn off history. Delete flows and history in the app or dashboard. Signing out removes tokens from the phone; deleting your account removes all server data.",
        "Contact" to
            "privacy@voicecontrol.app",
    )
}
