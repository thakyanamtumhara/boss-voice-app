package com.ketu.boss

import org.json.JSONArray

/**
 * Decides whether a transcript contains the wake word.
 *
 * The wake word is stored as *what the recogniser actually heard* when Ketu
 * taught it, so "Ketu" can be the wake word even though no English model has
 * that word: the model might hear "kate to", and that is what we match on.
 */
object WakeMatcher {

    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Vosk grammar for the wake stage — a tiny decode graph instead of the
     *  full language model, which is most of the battery saving. */
    fun grammarJson(phrases: List<String>): String {
        val a = JSONArray()
        phrases.map { normalize(it) }.filter { it.isNotBlank() }.distinct().forEach { a.put(it) }
        a.put("[unk]")
        return a.toString()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0 = loose, 1 = normal, 2 = strict. */
    private fun tolerance(sensitivity: Int) = when (sensitivity) {
        0 -> 0.34
        2 -> 0.10
        else -> 0.22
    }

    fun matches(heard: String, phrases: List<String>, sensitivity: Int = 1): Boolean {
        val h = normalize(heard)
        if (h.isBlank()) return false
        val tol = tolerance(sensitivity)
        val hWords = h.split(" ")

        for (raw in phrases) {
            val p = normalize(raw)
            if (p.isBlank()) continue
            if (h.contains(p)) return true

            val n = p.split(" ").size
            // Slide a window the length of the phrase across the transcript so
            // a wake word buried in a longer utterance still lands.
            for (start in 0..(hWords.size - n).coerceAtLeast(0)) {
                val window = hWords.subList(start, minOf(start + n, hWords.size)).joinToString(" ")
                if (window.isBlank()) continue
                val d = levenshtein(window, p)
                if (d.toDouble() / p.length <= tol) return true
            }
        }
        return false
    }

    /** Everything after the wake word, so "hey boss set an alarm" works in one
     *  breath without waiting for the chime. */
    fun tail(heard: String, phrases: List<String>): String {
        val h = normalize(heard)
        var best = -1
        for (raw in phrases) {
            val p = normalize(raw)
            if (p.isBlank()) continue
            val i = h.indexOf(p)
            if (i >= 0) best = maxOf(best, i + p.length)
        }
        return if (best < 0) "" else h.substring(best).trim()
    }
}
