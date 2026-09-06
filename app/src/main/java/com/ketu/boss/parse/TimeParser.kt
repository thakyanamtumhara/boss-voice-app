package com.ketu.boss.parse

import java.util.Calendar

/**
 * Pulls a time out of a spoken sentence. Handles plain English, the clock
 * idioms ("half past six", "quarter to seven") and the Hinglish ones Ketu
 * actually says ("subah 7 baje", "saade 6", "20 minute baad").
 *
 * Pure JVM code on purpose — it is unit-tested without a device.
 */
object TimeParser {

    enum class Meridiem { NONE, AM, PM, NIGHT }

    data class Hit(
        /** Non-null for "in 20 minutes" style. */
        val relativeSeconds: Int? = null,
        /** 0..12 as spoken; resolve() turns it into a 24-hour time. */
        val hour: Int = -1,
        val minute: Int = 0,
        val meridiem: Meridiem = Meridiem.NONE,
        val dayOffset: Int = 0,
        val dayExplicit: Boolean = false,
        val consumed: List<IntRange> = emptyList()
    )

    private const val UNIT = "(seconds?|secs?|minutes?|mins?|minit|hours?|hrs?|ghante|ghanta|days?|din)"
    private val N = Words.NUM
    private val NS = Words.NUMS

    private fun unitSeconds(u: String): Int = when {
        u.startsWith("sec") -> 1
        u.startsWith("min") -> 60
        u.startsWith("hour") || u.startsWith("hr") || u.startsWith("ghant") -> 3600
        else -> 86400
    }

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // ---------- relative ----------

    private val REL_HALF_HOUR = r("\\b(?:in|after|within)\\s+half\\s+an?\\s+$UNIT\\b")
    private val REL_ARTICLE = r("\\b(?:in|after|within)\\s+(?:an?|one)\\s+$UNIT\\b")
    private val REL_N = r("\\b(?:in|after|within)\\s+($NS)\\s*(?:and\\s+a\\s+half\\s+)?$UNIT\\b")
    private val REL_TRAILING = r("\\b($NS)\\s*$UNIT\\s+(?:baad|bad|later|from\\s+now)\\b")
    private val REL_HALF_SUFFIX = r("\\b(?:in|after|within)\\s+($NS)\\s+and\\s+a\\s+half\\s+$UNIT\\b")

    private fun findRelative(t: String): Hit? {
        REL_HALF_SUFFIX.find(t)?.let { m ->
            val n = Words.toNumber(m.groupValues[1]) ?: return@let
            val u = unitSeconds(m.groupValues[2])
            return Hit(relativeSeconds = n * u + u / 2, consumed = listOf(m.range))
        }
        REL_HALF_HOUR.find(t)?.let { m ->
            return Hit(relativeSeconds = unitSeconds(m.groupValues[1]) / 2, consumed = listOf(m.range))
        }
        REL_N.find(t)?.let { m ->
            val n = Words.toNumber(m.groupValues[1])
            if (n != null) return Hit(relativeSeconds = n * unitSeconds(m.groupValues[2]), consumed = listOf(m.range))
        }
        REL_ARTICLE.find(t)?.let { m ->
            return Hit(relativeSeconds = unitSeconds(m.groupValues[1]), consumed = listOf(m.range))
        }
        REL_TRAILING.find(t)?.let { m ->
            val n = Words.toNumber(m.groupValues[1])
            if (n != null) return Hit(relativeSeconds = n * unitSeconds(m.groupValues[2]), consumed = listOf(m.range))
        }
        return null
    }

    private val DUR_ARTICLE = r("\\b(?:for\\s+)?(?:an?|one)\\s+$UNIT\\b")
    private val DUR_HALF = r("\\b(?:for\\s+)?half\\s+an?\\s+$UNIT\\b")
    private val DUR_HALF_SUFFIX = r("\\b(?:for\\s+)?($NS)\\s+and\\s+a\\s+half\\s+$UNIT\\b")
    private val DUR_N = r("\\b(?:for\\s+|of\\s+)?($NS)\\s*$UNIT\\b")

    /**
     * A bare duration with no "in"/"after" to anchor it — "timer for 10
     * minutes", "10 minute ka timer". Only safe to call once the sentence is
     * already known to be about a duration.
     */
    fun findDuration(text: String): Hit? {
        val t = text.lowercase()
        findRelative(t)?.let { return it }
        DUR_HALF_SUFFIX.find(t)?.let { m ->
            val n = Words.toNumber(m.groupValues[1]) ?: return@let
            val u = unitSeconds(m.groupValues[2])
            return Hit(relativeSeconds = n * u + u / 2, consumed = listOf(m.range))
        }
        DUR_HALF.find(t)?.let { m ->
            return Hit(relativeSeconds = unitSeconds(m.groupValues[1]) / 2, consumed = listOf(m.range))
        }
        DUR_N.find(t)?.let { m ->
            val n = Words.toNumber(m.groupValues[1])
            if (n != null) return Hit(relativeSeconds = n * unitSeconds(m.groupValues[2]), consumed = listOf(m.range))
        }
        DUR_ARTICLE.find(t)?.let { m ->
            return Hit(relativeSeconds = unitSeconds(m.groupValues[1]), consumed = listOf(m.range))
        }
        return null
    }

    // ---------- day ----------

    private val DAY_AFTER = r("\\b(day\\s+after\\s+tomorrow|parso|parsoon)\\b")
    private val TOMORROW = r("\\b(tomorrow|tommorow|tomorow|tomorrw|kal)\\b")
    private val TODAY = r("\\b(today|aaj|tonight)\\b")
    private val WEEKDAYS = listOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY
    )

    private data class DayHit(val offset: Int, val range: IntRange, val explicit: Boolean)

    private fun findDay(t: String, now: Calendar): DayHit? {
        DAY_AFTER.find(t)?.let { return DayHit(2, it.range, true) }
        TOMORROW.find(t)?.let { return DayHit(1, it.range, true) }
        TODAY.find(t)?.let { return DayHit(0, it.range, true) }
        for ((name, dow) in WEEKDAYS) {
            val m = r("\\b(?:on\\s+|next\\s+)?$name\\b").find(t) ?: continue
            var delta = dow - now.get(Calendar.DAY_OF_WEEK)
            if (delta <= 0) delta += 7
            return DayHit(delta, m.range, true)
        }
        return null
    }

    // ---------- meridiem ----------

    private val M_MORNING = r("\\b(morning|subah|subha|sube|savere|sawere)\\b")
    private val M_AFTERNOON = r("\\b(afternoon|dopahar|dopeher|dupahar|dopahr)\\b")
    private val M_EVENING = r("\\b(evening|shaam|sham|shyam)\\b")
    private val M_NIGHT = r("\\b(night|raat|raat\\s+ko)\\b")

    private data class MerHit(val m: Meridiem, val range: IntRange)

    private fun findMeridiemWord(t: String): MerHit? {
        M_MORNING.find(t)?.let { return MerHit(Meridiem.AM, it.range) }
        M_AFTERNOON.find(t)?.let { return MerHit(Meridiem.PM, it.range) }
        M_EVENING.find(t)?.let { return MerHit(Meridiem.PM, it.range) }
        M_NIGHT.find(t)?.let { return MerHit(Meridiem.NIGHT, it.range) }
        return null
    }

    // ---------- absolute ----------

    // "am"/"pm" is only ever read directly after a number, so the "I am" trap
    // never fires.
    private const val MER = "\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)"

    private val A_NOON = r("\\b(noon|12\\s*noon)\\b")
    private val A_MIDNIGHT = r("\\bmidnight\\b")
    private val A_DEDH = r("\\b(dedh|derh)\\s*(?:baje|bje)?\\b")
    private val A_DHAI = r("\\b(dhai|dhaai|dhayi)\\s*(?:baje|bje)?\\b")
    private val A_COLON = r("\\b(\\d{1,2})\\s*[:.]\\s*(\\d{2})(?:$MER)?")
    private val A_HALF_PAST = r("\\bhalf\\s+past\\s+($N)\\b")
    private val A_QTR_PAST = r("\\bquarter\\s+past\\s+($N)\\b")
    private val A_QTR_TO = r("\\bquarter\\s+to\\s+($N)\\b")
    private val A_MIN_PAST = r("\\b($NS)\\s+(?:minutes?\\s+)?past\\s+($N)\\b")
    private val A_MIN_TO = r("\\b($NS)\\s+(?:minutes?\\s+)?to\\s+($N)\\b")
    private val A_SAADE = r("\\b(?:saade|sade|sadhe|sarhe)\\s+($N)\\b")
    private val A_SAWA = r("\\b(?:sawa|sava)\\s+($N)\\b")
    private val A_PAUNE = r("\\b(?:paune|pone|poune)\\s+($N)\\b")
    private val A_OH = r("\\b($N)\\s+(?:oh|o)\\s+($N)\\b")
    private val A_H_M = r("\\b($N)\\s+($NS)(?:$MER)?\\s*(?:baje|bje|o'?\\s?clock|oclock)?\\b")
    private val A_OCLOCK = r("\\b($N)\\s*(?:baje|bje|o'?\\s?clock|oclock)(?:$MER)?")
    private val A_MER = r("\\b($N)$MER")
    private val A_AT = r("\\b(?:at|for|by|around|about)\\s+($N)\\b")
    private val A_BARE = r("\\b($N)\\b")

    private fun mer(s: String?): Meridiem = when {
        s.isNullOrBlank() -> Meridiem.NONE
        s.replace(".", "").replace(" ", "").startsWith("a") -> Meridiem.AM
        else -> Meridiem.PM
    }

    private fun findAbsolute(t: String): Hit? {
        A_MIDNIGHT.find(t)?.let { return Hit(hour = 0, minute = 0, meridiem = Meridiem.AM, consumed = listOf(it.range)) }
        A_NOON.find(t)?.let { return Hit(hour = 12, minute = 0, meridiem = Meridiem.PM, consumed = listOf(it.range)) }
        A_DEDH.find(t)?.let { return Hit(hour = 1, minute = 30, consumed = listOf(it.range)) }
        A_DHAI.find(t)?.let { return Hit(hour = 2, minute = 30, consumed = listOf(it.range)) }

        A_COLON.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: return@let
            if (h in 0..23 && min in 0..59) {
                return Hit(hour = h, minute = min, meridiem = mer(m.groupValues.getOrNull(3)), consumed = listOf(m.range))
            }
        }
        A_HALF_PAST.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { return Hit(hour = it, minute = 30, consumed = listOf(m.range)) }
        }
        A_QTR_PAST.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { return Hit(hour = it, minute = 15, consumed = listOf(m.range)) }
        }
        A_QTR_TO.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { return Hit(hour = prev(it), minute = 45, consumed = listOf(m.range)) }
        }
        A_SAADE.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { return Hit(hour = it, minute = 30, consumed = listOf(m.range)) }
        }
        A_SAWA.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { return Hit(hour = it, minute = 15, consumed = listOf(m.range)) }
        }
        A_PAUNE.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { return Hit(hour = prev(it), minute = 45, consumed = listOf(m.range)) }
        }
        A_MIN_PAST.find(t)?.let { m ->
            val min = Words.toNumber(m.groupValues[1]); val h = Words.toNumber(m.groupValues[2])
            if (min != null && h != null && min in 1..59 && h in 0..12) {
                return Hit(hour = h, minute = min, consumed = listOf(m.range))
            }
        }
        A_MIN_TO.find(t)?.let { m ->
            val min = Words.toNumber(m.groupValues[1]); val h = Words.toNumber(m.groupValues[2])
            if (min != null && h != null && min in 1..59 && h in 0..12) {
                return Hit(hour = prev(h), minute = 60 - min, consumed = listOf(m.range))
            }
        }
        A_OH.find(t)?.let { m ->
            val h = Words.toNumber(m.groupValues[1]); val min = Words.toNumber(m.groupValues[2])
            if (h != null && min != null && h in 0..12 && min in 0..9) {
                return Hit(hour = h, minute = min, consumed = listOf(m.range))
            }
        }
        A_H_M.find(t)?.let { m ->
            val h = Words.toNumber(m.groupValues[1]); val min = Words.toNumber(m.groupValues[2])
            // Only when it really reads as a clock time: "seven thirty", not
            // "seven seven".
            if (h != null && min != null && h in 1..12 && min in 0..59 &&
                (min >= 20 || m.groupValues[2].trim().length == 2 || min == 15 || min == 10 || min == 5)
            ) {
                return Hit(hour = h, minute = min, meridiem = mer(m.groupValues.getOrNull(3)), consumed = listOf(m.range))
            }
        }
        A_OCLOCK.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { h ->
                if (h in 0..23) return Hit(hour = h, minute = 0, meridiem = mer(m.groupValues.getOrNull(2)), consumed = listOf(m.range))
            }
        }
        A_MER.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { h ->
                if (h in 0..23) return Hit(hour = h, minute = 0, meridiem = mer(m.groupValues[2]), consumed = listOf(m.range))
            }
        }
        A_AT.find(t)?.let { m ->
            Words.toNumber(m.groupValues[1])?.let { h ->
                if (h in 0..23) return Hit(hour = h, minute = 0, consumed = listOf(m.range))
            }
        }
        return null
    }

    private fun prev(h: Int) = if (h <= 1) 12 else h - 1

    // ---------- entry point ----------

    /** Finds a time in [text]. [nowMillis] only matters for weekday names. */
    fun find(text: String, nowMillis: Long = System.currentTimeMillis()): Hit? {
        val t = text.lowercase()
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

        findRelative(t)?.let { return it }

        val abs = findAbsolute(t) ?: return null
        val day = findDay(t, now)
        val merWord = findMeridiemWord(t)

        val consumed = buildList {
            addAll(abs.consumed)
            day?.let { add(it.range) }
            merWord?.let { add(it.range) }
        }
        return abs.copy(
            meridiem = if (abs.meridiem != Meridiem.NONE) abs.meridiem else (merWord?.m ?: Meridiem.NONE),
            dayOffset = day?.offset ?: 0,
            dayExplicit = day?.explicit ?: false,
            consumed = consumed
        )
    }

    /**
     * Like [find], but will also read a bare number as an hour. Only safe when
     * the sentence is already known to be about a time — "alarm 7". A reminder
     * must never use this ("remind me to buy 2 shirts" is not 2 o'clock).
     */
    fun findLoose(text: String, nowMillis: Long = System.currentTimeMillis()): Hit? {
        find(text, nowMillis)?.let { return it }
        val t = text.lowercase()
        val m = A_BARE.find(t) ?: return null
        val h = Words.toNumber(m.groupValues[1]) ?: return null
        if (h !in 0..23) return null
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val day = findDay(t, now)
        val merWord = findMeridiemWord(t)
        return Hit(
            hour = h, minute = 0,
            meridiem = merWord?.m ?: Meridiem.NONE,
            dayOffset = day?.offset ?: 0,
            dayExplicit = day?.explicit ?: false,
            consumed = buildList { add(m.range); day?.let { add(it.range) }; merWord?.let { add(it.range) } }
        )
    }

    /** Turns a hit into a wall-clock instant, always in the future. */
    fun resolve(hit: Hit, nowMillis: Long = System.currentTimeMillis()): Long {
        if (hit.relativeSeconds != null) return nowMillis + hit.relativeSeconds * 1000L

        fun at(h: Int, m: Int, plusDays: Int): Long =
            Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, plusDays)
            }.timeInMillis

        val h = hit.hour
        val m = hit.minute

        val known = when (hit.meridiem) {
            Meridiem.AM -> (h % 12)
            Meridiem.PM -> (h % 12) + 12
            // "raat 2 baje" is 2 AM; "raat 9 baje" is 9 PM.
            Meridiem.NIGHT -> if (h in 1..4) h else (h % 12) + 12
            Meridiem.NONE -> null
        }

        if (known != null || h > 12) {
            val h24 = known ?: h
            var t = at(h24, m, hit.dayOffset)
            if (!hit.dayExplicit && t <= nowMillis) t = at(h24, m, hit.dayOffset + 1)
            return t
        }

        if (hit.dayExplicit && hit.dayOffset > 0) {
            // A named future day cannot disambiguate itself, so read a bare
            // hour the way a person would: 1-4 is afternoon, 5-11 is morning.
            val h24 = when (h) { in 1..4 -> h + 12; 12 -> 12; 0 -> 0; else -> h }
            return at(h24, m, hit.dayOffset)
        }

        // Bare hour, today: whichever reading comes round first.
        val cands = linkedSetOf(h % 12, (h % 12) + 12).map { c ->
            var t = at(c, m, hit.dayOffset)
            if (t <= nowMillis) t = at(c, m, hit.dayOffset + 1)
            t
        }
        return cands.min()
    }

    /** Removes the words the time expression ate, so a label can be recovered. */
    fun strip(text: String, hit: Hit): String {
        if (hit.consumed.isEmpty()) return text
        val sb = StringBuilder(text)
        hit.consumed.sortedByDescending { it.first }.forEach { rr ->
            val from = rr.first.coerceIn(0, sb.length)
            val to = (rr.last + 1).coerceIn(from, sb.length)
            sb.replace(from, to, " ")
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
