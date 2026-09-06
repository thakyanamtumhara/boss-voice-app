package com.ketu.boss.act

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class Match(val name: String, val number: String, val score: Double)

/**
 * Finds the person you meant. Voice gets names wrong often enough that an
 * exact match alone is useless, so this scores every contact and only acts on
 * a clear winner.
 */
object ContactFinder {

    private fun norm(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun score(query: String, name: String): Double {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        val qWords = q.split(" ").filter { it.isNotBlank() }
        val nWords = n.split(" ").filter { it.isNotBlank() }
        // A first-name hit is the common case: "call rajesh" -> "Rajesh Kumar".
        if (nWords.isNotEmpty() && qWords.isNotEmpty() && nWords.first() == qWords.first()) return 0.94
        if (n.startsWith(q)) return 0.9
        if (n.contains(q)) return 0.82
        val shared = qWords.count { qw -> nWords.any { it == qw } }
        if (shared > 0) return 0.7 + 0.05 * shared
        val d = levenshtein(q, n)
        val ratio = 1.0 - d.toDouble() / maxOf(q.length, n.length)
        // Word-level near-miss: "rajish" for "Rajesh".
        val best = nWords.minOfOrNull { nw ->
            qWords.minOfOrNull { qw -> levenshtein(qw, nw).toDouble() / maxOf(qw.length, nw.length) } ?: 1.0
        } ?: 1.0
        return maxOf(ratio, 1.0 - best) * 0.8
    }

    /** Ranked candidates for [query]. Empty if contacts cannot be read. */
    fun search(ctx: Context, query: String, limit: Int = 4): List<Match> {
        if (!hasPermission(ctx)) return emptyList()
        val digits = query.filter { it.isDigit() }
        if (digits.length >= 7 && query.none { it.isLetter() }) {
            return listOf(Match(query.trim(), query.filter { it.isDigit() || it == '+' }, 1.0))
        }

        val out = LinkedHashMap<String, Match>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null
            )?.use { c ->
                val iName = c.getColumnIndex(proj[0])
                val iNum = c.getColumnIndex(proj[1])
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val num = c.getString(iNum) ?: continue
                    val s = score(query, name)
                    if (s < 0.55) continue
                    val key = name + "|" + num.filter { it.isDigit() }.takeLast(10)
                    val existing = out[key]
                    if (existing == null || existing.score < s) out[key] = Match(name, num, s)
                }
            }
        }
        return out.values.sortedByDescending { it.score }.take(limit)
    }

    /** A single confident answer, or null when it is too close to call. */
    fun best(ctx: Context, query: String): Match? {
        val all = search(ctx, query)
        val top = all.firstOrNull() ?: return null
        if (top.score < 0.7) return null
        val second = all.getOrNull(1)
        // Two contacts scoring nearly the same means guessing, so don't.
        if (second != null && top.score - second.score < 0.03 &&
            top.number.filter { it.isDigit() } != second.number.filter { it.isDigit() }
        ) return null
        return top
    }
}
