package com.ketu.boss.parse

/** Number words, English plus the Hindi ones that survive an en-IN transcript. */
object Words {

    // Deliberately NOT here: "do", "no", "o". They are Hindi 2 / 9 / 0, but they
    // are also ordinary English words ("do it", "no", "o"), and a false number
    // in the middle of a command is worse than missing a Hindi digit.
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "ek" to 1, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "chhah" to 6, "saat" to 7, "aath" to 8, "nau" to 9,
        "das" to 10, "dus" to 10, "gyarah" to 11, "barah" to 12
    )

    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50
    )

    private val all = units + tens

    /** "twenty five" -> 25, "seven" -> 7, "45" -> 45. Null if not a number. */
    fun toNumber(raw: String): Int? {
        val s = raw.trim().lowercase().replace("-", " ").replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val parts = s.split(" ").filter { it.isNotBlank() && it != "and" }
        if (parts.isEmpty()) return null
        var total: Int? = null
        for (p in parts) {
            val v = all[p] ?: p.toIntOrNull() ?: return null
            total = (total ?: 0) + v
        }
        return total
    }

    /** Alternation matching one number token, digits or a word. Wrap in \b at use. */
    val NUM: String = "(?:\\d{1,4}|" +
        all.keys.sortedByDescending { it.length }.joinToString("|") + ")"

    /** One or two number tokens, so "twenty five" reads as one number. */
    val NUMS: String = "(?:$NUM(?:[ -]$NUM)?)"
}
