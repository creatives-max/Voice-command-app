package com.voicecontrol.core.model

/** The app's light or dark look; [SYSTEM] follows the phone's setting. */
enum class ThemeMode(val label: String) {
    SYSTEM("Same as phone"),
    LIGHT("Light"),
    DARK("Dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Unknown stored values (e.g. from a newer version) fall back to following the phone. */
        fun parse(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
