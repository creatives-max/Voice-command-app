package com.voicecontrol.application.match

/**
 * Converts a screen signature ("pkg|Activity|text_field/email:email address|button:continue")
 * into natural text for embedding. Package name is excluded (matching is already scoped to it).
 */
object SignatureText {
    fun of(signature: String): String {
        val parts = signature.split('|')
        val activity = parts.getOrNull(1).orEmpty()
            .replace(Regex("([a-z])([A-Z])"), "$1 $2").removeSuffix("Activity").trim()
        val elements = parts.drop(2).filter { it.isNotBlank() }.map { part ->
            val kindAndType = part.substringBefore(':')
            val label = part.substringAfter(':', "")
            val kind = kindAndType.substringBefore('/').replace('_', ' ')
            val type = kindAndType.substringAfter('/', "").replace('_', ' ')
            listOf(kind, type, label).filter { it.isNotBlank() }.joinToString(" ")
        }
        return (listOf(activity) + elements).filter { it.isNotBlank() }.joinToString(". ")
    }
}
