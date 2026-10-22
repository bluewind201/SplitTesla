package com.splittesla.core.assistant

/**
 * Finds the driver's chosen call word in a speech recognizer transcript and
 * returns what was said after it. Spaces, punctuation and case are ignored,
 * and a word of three or more letters may be off by one letter (three-letter
 * words by a swapped letter only), because the recognizer often spells a
 * made-up name slightly differently.
 */
object WakeWord {

    /** The command after the wake word ("" when the word was said alone), or null when it was not said. */
    fun match(transcript: String, wakeWord: String): String? {
        val wake = normalize(wakeWord)
        if (wake.isEmpty()) return null
        // Normalized text plus, for each kept character, its index in the original.
        val kept = StringBuilder()
        val origin = ArrayList<Int>()
        transcript.forEachIndexed { i, c ->
            if (c.isLetterOrDigit()) {
                kept.append(c.lowercaseChar())
                origin += i
            }
        }
        val text = kept.toString()
        val end = findEnd(text, wake) ?: return null
        val rest = if (end >= origin.size) "" else transcript.substring(origin[end])
        return rest.trim().trimStart(',', '.', '!', '?', ' ').trim()
    }

    /** The first wake word occurrence across the recognizer's alternatives, with its command. */
    fun matchAny(alternatives: List<String>, wakeWord: String): String? =
        alternatives.firstNotNullOfOrNull { match(it, wakeWord) }

    fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    /** Index in [text] just past the wake word, exact match first, then one edit off. */
    private fun findEnd(text: String, wake: String): Int? {
        val exact = text.indexOf(wake)
        if (exact >= 0) return exact + wake.length
        if (wake.length < 3) return null
        // A three-letter word may only have one letter swapped; dropping one would leave two letters.
        val lengths = if (wake.length == 3) 3..3 else (wake.length - 1)..(wake.length + 1)
        for (start in text.indices) {
            for (len in lengths) {
                if (start + len > text.length) continue
                if (editDistance(text.substring(start, start + len), wake) <= 1) return start + len
            }
        }
        return null
    }

    internal fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
