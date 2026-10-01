package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.InstalledApp

/** Finds the installed app the user named ("whatsapp", "यूट्यूब", "google pay"). */
object AppMatcher {
    fun find(spoken: String, apps: List<InstalledApp>): InstalledApp? {
        val target = ButtonMatcher.normalize(spoken).replace(" ", "")
        if (target.isEmpty()) return null
        return apps
            .map { it to score(target, ButtonMatcher.normalize(it.label).replace(" ", "")) }
            .filter { it.second >= MIN_SCORE }
            .maxWithOrNull(compareBy<Pair<InstalledApp, Double>> { it.second }.thenBy { -it.first.label.length })
            ?.first
    }

    private fun score(target: String, label: String): Double = when {
        label.isEmpty() -> 0.0
        target == label -> 1.0
        label.startsWith(target) || target.startsWith(label) -> 0.9
        else -> maxOf(ButtonMatcher.score(target, label), soundScore(target, label))
    }

    /**
     * Names said in Indian scripts come back transliterated ("यूट्यूब" → "yutyub", "फोनपे" → "phonape"):
     * compare how they sound, by their consonants, instead of their spelling.
     */
    internal fun soundScore(a: String, b: String): Double {
        val x = skeleton(a)
        val y = skeleton(b)
        if (x.length < 2 || y.length < 2) return 0.0
        val distance = levenshtein(x, y)
        return (1.0 - distance.toDouble() / maxOf(x.length, y.length)) * 0.95
    }

    internal fun skeleton(word: String): String {
        val s = word.lowercase().filter { it in 'a'..'z' }
            .replace("ph", "f").replace("ck", "k").replace("ce", "se").replace("ci", "si")
            .replace('c', 'k').replace('q', 'k').replace('w', 'v').replace('g', 'j').replace('z', 'j')
        if (s.isEmpty()) return ""
        val out = StringBuilder().append(s[0])
        for (ch in s.drop(1)) {
            if (ch in "aeiouyh") continue
            if (out.last() != ch) out.append(ch)
        }
        return out.toString()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private const val MIN_SCORE = 0.75
}
