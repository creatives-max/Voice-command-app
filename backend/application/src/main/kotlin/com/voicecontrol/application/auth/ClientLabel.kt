package com.voicecontrol.application.auth

/** Turns a User-Agent into words people recognise on their sessions list ("Chrome on Windows"). */
object ClientLabel {
    private val APP = Regex("VoiceControl-Android/([0-9][\\w.\\-]*)")

    fun describe(userAgent: String?): String? {
        val ua = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        APP.find(ua)?.let { return "VoiceControl app ${it.groupValues[1]} on Android" }
        if (ua.startsWith("Ktor client")) return "VoiceControl app"
        val browser = when {
            "Edg/" in ua || "EdgA/" in ua -> "Edge"
            "OPR/" in ua -> "Opera"
            "SamsungBrowser/" in ua -> "Samsung Internet"
            "Firefox/" in ua || "FxiOS/" in ua -> "Firefox"
            "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
            "Safari/" in ua -> "Safari"
            else -> null
        }
        val os = when {
            "Windows" in ua -> "Windows"
            "Android" in ua -> "Android"
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "CrOS" in ua -> "ChromeOS"
            "Mac OS X" in ua || "Macintosh" in ua -> "macOS"
            "Linux" in ua -> "Linux"
            else -> null
        }
        return when {
            browser != null && os != null -> "$browser on $os"
            browser != null -> browser
            os != null -> "Browser on $os"
            else -> ua.take(60)
        }
    }
}
