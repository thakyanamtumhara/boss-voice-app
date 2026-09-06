package com.ketu.boss.parse

/**
 * Turns one spoken sentence into a [Command].
 *
 * Rules, not a language model: it runs instantly, offline, for free, and it is
 * predictable enough to unit-test. Order matters — "remind me to call the CA"
 * has to be read as a reminder, not as a call.
 */
object CommandParser {

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val WAKE_PREFIX = r("^\\s*(?:hey|ok|okay|hi|hello|arre|are)?\\s*(?:boss|jarvis|computer)?[,.\\s]*")
    private val POLITE = r("\\b(please|plz|kripya|zara|thoda|abhi|now|for me|mere liye)\\b")
    private val TRAILING_HI = r("\\s*(?:kar\\s*do|kardo|kar\\s*dena|karo|kar\\s*de|de\\s*do|dena|do\\s*na|na)\\s*$")

    fun parse(raw: String, nowMillis: Long = System.currentTimeMillis()): Command {
        val text = clean(raw)
        if (text.isBlank()) return Command.Unknown(raw.trim())

        cancel(text)?.let { return it }
        timer(text)?.let { return it }
        alarm(text, nowMillis)?.let { return it }
        reminder(text, nowMillis)?.let { return it }
        showReminders(text)?.let { return it }
        call(text)?.let { return it }
        message(text)?.let { return it }
        navigate(text)?.let { return it }
        torch(text)?.let { return it }
        volume(text)?.let { return it }
        timeNow(text)?.let { return it }
        battery(text)?.let { return it }
        play(text)?.let { return it }
        openApp(text)?.let { return it }
        search(text)?.let { return it }
        return Command.Unknown(text)
    }

    // ---------- normalisation ----------

    private fun clean(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace("’", "'").replace(Regex("[\\u201c\\u201d]"), "\"")
        s = WAKE_PREFIX.replace(s, "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Trims filler off an extracted argument without eating real words. */
    private fun tidy(s: String): String {
        var t = s.trim().trim('.', ',', '!', '?', ':', ';', '"', '\'')
        t = POLITE.replace(t, " ")
        t = TRAILING_HI.replace(t, "")
        t = t.replace(Regex("^(?:to|for|the|a|an|ko|se|par|pe)\\s+"), "")
        t = t.replace(Regex("\\s+(?:ko|ke\\s+liye|ka|ki)$"), "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val LEAD_FILLER = setOf(
        "me", "mujhe", "us", "to", "at", "on", "by", "in", "for", "that", "about",
        "ki", "ka", "ke", "the", "a", "an", "and", "ko", "please", "pls", "is", "it"
    )
    private val TAIL_FILLER = setOf(
        "at", "on", "by", "in", "for", "to", "about", "ki", "ka", "ke", "liye",
        "and", "the", "a", "an", "please", "pls", "na", "par", "pe", "se"
    )

    /**
     * Removing the time expression leaves prepositions stranded on both ends —
     * "remind me at 5 to call the CA" becomes "me at to call the ca". This
     * peels those off without ever eating the last real word.
     */
    private fun scrub(s: String): String {
        var toks = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        while (toks.size > 1 && toks.first().lowercase() in LEAD_FILLER) toks = toks.drop(1)
        while (toks.size > 1 && toks.last().lowercase() in TAIL_FILLER) toks = toks.dropLast(1)
        if (toks.size == 1) {
            val only = toks[0].lowercase()
            if (only in LEAD_FILLER || only in TAIL_FILLER) return ""
        }
        return toks.joinToString(" ")
    }

    private fun labelOrNull(s: String): String? =
        scrub(tidy(s)).takeIf { it.length >= 2 && !it.matches(Regex("^(?:a|an|the|it|that|set|for|of|me|my)$")) }

    // ---------- intents ----------

    private val CANCEL = r("^(?:stop|cancel|never\\s*mind|nevermind|forget\\s*it|nothing|quit|exit|chhodo|chodo|rehne\\s*do|band\\s*karo)\\b")
    private fun cancel(t: String) = if (CANCEL.containsMatchIn(t) && t.split(" ").size <= 3) Command.Cancel else null

    private val TIMER = r("\\b(timer|stop\\s?watch|countdown)\\b")
    private fun timer(t: String): Command? {
        if (!TIMER.containsMatchIn(t)) return null
        val d = TimeParser.findDuration(t) ?: return Command.NeedTime(Command.NeedTime.Kind.TIMER, null)
        val secs = d.relativeSeconds ?: return Command.NeedTime(Command.NeedTime.Kind.TIMER, null)
        val label = labelOrNull(TimeParser.strip(t, d).let { TIMER.replace(it, " ") }.let { stripVerbs(it) })
        return Command.Timer(secs.coerceAtLeast(1), label)
    }

    private val ALARM = r("\\b(alarm|alaram|alaram|alarm\\s*clock)\\b")
    private val WAKE_ME = r("\\b(wake\\s+me|wake\\s+up|uthana|utha\\s*dena|jaga\\s*dena|jagana|jaga\\s*do)\\b")
    private fun alarm(t: String, now: Long): Command? {
        if (!ALARM.containsMatchIn(t) && !WAKE_ME.containsMatchIn(t)) return null
        val hit = TimeParser.findLoose(t, now) ?: TimeParser.findDuration(t)
            ?: return Command.NeedTime(Command.NeedTime.Kind.ALARM, null)
        val at = TimeParser.resolve(hit, now)
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        var rest = TimeParser.strip(t, hit)
        rest = ALARM.replace(rest, " "); rest = WAKE_ME.replace(rest, " ")
        return Command.Alarm(
            hour = c.get(java.util.Calendar.HOUR_OF_DAY),
            minute = c.get(java.util.Calendar.MINUTE),
            atMillis = at,
            label = labelOrNull(stripVerbs(rest))
        )
    }

    private val REMIND = r("\\b(remind|reminder|reminde|yaad\\s*dila|yaad\\s*dilana|yaad\\s*rakh|yaad\\s*karana)\\b")
    private val REMIND_PREFIX = r("^(?:remind|reminder|reminde)\\s*(?:me|mujhe|us)?\\s*(?:to|that|about|ki|for)?\\s*")
    private fun reminder(t: String, now: Long): Command? {
        if (!REMIND.containsMatchIn(t)) return null
        val hit = TimeParser.find(t, now)
        var body = if (hit != null) TimeParser.strip(t, hit) else t
        body = REMIND.replace(body, " ").replace(Regex("\\s+"), " ").trim()
        body = REMIND_PREFIX.replace(body, "")
        body = body.replace(Regex("^(?:me|mujhe)\\s+(?:to|ki|that)?\\s*"), "")
        body = scrub(tidy(body))
        if (hit == null) {
            return Command.NeedTime(Command.NeedTime.Kind.REMINDER, body.ifBlank { null })
        }
        return Command.Reminder(TimeParser.resolve(hit, now), body.ifBlank { "reminder" })
    }

    private val SHOW_REM = r("\\b(?:my|pending|list|show|what\\s+are\\s+my|kaunse)\\b.{0,12}\\breminders?\\b")
    private fun showReminders(t: String) = if (SHOW_REM.containsMatchIn(t)) Command.ShowReminders else null

    private val PHONE = r("(\\+?\\d[\\d\\s\\-]{6,14}\\d)")
    private val CALL_EN = r("\\b(?:call|phone|dial|ring)\\b\\s*(?:up\\s+)?(?:to\\s+)?(.*)$")
    private val CALL_HI = r("^(.+?)\\s+ko\\s+(?:call|phone|fon|ring)\\b")
    private fun call(t: String): Command? {
        if (!r("\\b(call|phone|dial|ring)\\b").containsMatchIn(t)) return null
        PHONE.find(t)?.let { m ->
            val digits = m.groupValues[1].filter { it.isDigit() || it == '+' }
            if (digits.filter { it.isDigit() }.length >= 7) return Command.Call(digits)
        }
        CALL_HI.find(t)?.let { m ->
            val who = tidy(m.groupValues[1])
            if (who.isNotBlank()) return Command.Call(who)
        }
        CALL_EN.find(t)?.let { m ->
            val who = tidy(m.groupValues[1])
            if (who.isNotBlank()) return Command.Call(who)
        }
        return Command.NeedTime(Command.NeedTime.Kind.CALL_WHO, null)
    }

    private val MSG_KEY = r("\\b(whats\\s?app|whatsapp|wattsapp|message|msg|text|sms)\\b")
    private val MSG_FULL = r("(?:whats\\s?app|whatsapp|wattsapp|message|msg|text|sms)\\s+(?:to\\s+)?(.+?)\\s+(?:saying|that|say|bol\\s*do|bolo|ki|about|:)\\s+(.+)$")
    private val MSG_TO = r("(?:whats\\s?app|whatsapp|wattsapp|message|msg|text|sms)\\s+(?:to\\s+)?(.+)$")
    private val MSG_HI = r("^(.+?)\\s+ko\\s+(?:whats\\s?app|whatsapp|message|msg)\\b\\s*(?:kar\\w*)?\\s*(?:ki|bolo|that)?\\s*(.*)$")
    private fun message(t: String): Command? {
        if (!MSG_KEY.containsMatchIn(t)) return null
        MSG_FULL.find(t)?.let { m ->
            val who = tidy(m.groupValues[1]); val body = tidy(m.groupValues[2])
            if (who.isNotBlank()) return Command.Message(who, body.ifBlank { null })
        }
        MSG_HI.find(t)?.let { m ->
            val who = tidy(m.groupValues[1]); val body = tidy(m.groupValues[2])
            if (who.isNotBlank()) return Command.Message(who, body.ifBlank { null })
        }
        MSG_TO.find(t)?.let { m ->
            val who = tidy(m.groupValues[1])
            if (who.isNotBlank()) return Command.Message(who, null)
        }
        return null
    }

    private val NAV = r("\\b(?:navigate\\s+to|directions?\\s+to|take\\s+me\\s+to|route\\s+to|drive\\s+to|how\\s+do\\s+i\\s+get\\s+to|map\\s+to|rasta)\\b\\s*(.*)$")
    private fun navigate(t: String): Command? {
        val m = NAV.find(t) ?: return null
        val place = tidy(m.groupValues[1])
        return if (place.isBlank()) null else Command.Navigate(place)
    }

    private val TORCH = r("\\b(torch|flash\\s?light|batti)\\b")
    private val OFFISH = r("\\b(off|band|bandh|close|nahi)\\b")
    private fun torch(t: String): Command? =
        if (TORCH.containsMatchIn(t)) Command.Torch(!OFFISH.containsMatchIn(t)) else null

    private fun volume(t: String): Command? {
        if (r("\\b(silent|vibrate\\s*mode|chup)\\b").containsMatchIn(t)) return Command.Volume(Command.Volume.Kind.SILENT)
        if (r("\\b(mute|volume\\s*(?:zero|0)|awaaz\\s*band)\\b").containsMatchIn(t)) return Command.Volume(Command.Volume.Kind.MUTE)
        if (r("\\b(ringer\\s*on|sound\\s*on|unmute|normal\\s*mode)\\b").containsMatchIn(t)) return Command.Volume(Command.Volume.Kind.LOUD)
        if (!r("\\bvolume|awaaz\\b").containsMatchIn(t)) return null
        r("\\bvolume\\s*(?:to\\s*)?(\\d{1,3})\\s*%?").find(t)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull()
            if (pct != null && pct in 0..100) return Command.Volume(Command.Volume.Kind.SET, pct)
        }
        if (r("\\b(up|badhao|zyada|loud|increase)\\b").containsMatchIn(t)) return Command.Volume(Command.Volume.Kind.UP)
        if (r("\\b(down|kam|low|decrease|ghatao)\\b").containsMatchIn(t)) return Command.Volume(Command.Volume.Kind.DOWN)
        return null
    }

    private val TIME_Q = r("\\b(?:what(?:'?s| is)?\\s+the\\s+time|what\\s+time\\s+is\\s+it|time\\s+kya|kitne\\s+baje|samay\\s+kya)\\b")
    private fun timeNow(t: String) = if (TIME_Q.containsMatchIn(t)) Command.TimeNow else null

    private val BATT_Q = r("\\b(battery|charge\\s+kitna|charging)\\b")
    private fun battery(t: String) = if (BATT_Q.containsMatchIn(t)) Command.BatteryNow else null

    private val PLAY = r("^(?:play|bajao|chalao)\\s+(.+)$")
    private fun play(t: String): Command? {
        val m = PLAY.find(t) ?: return null
        val q = tidy(m.groupValues[1].replace(Regex("\\b(?:on|pe|par)\\s+(?:youtube|spotify|gaana)\\b"), " "))
        return if (q.isBlank()) null else Command.Play(q)
    }

    private val OPEN_EN = r("^(?:open|launch|start|khol\\s*do|kholo)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$")
    private val OPEN_HI = r("^(.+?)\\s+(?:khol\\s*do|kholo|open\\s*karo|chalu\\s*karo)$")
    private fun openApp(t: String): Command? {
        OPEN_HI.find(t)?.let { m ->
            val n = tidy(m.groupValues[1]); if (n.isNotBlank()) return Command.OpenApp(n)
        }
        OPEN_EN.find(t)?.let { m ->
            val n = tidy(m.groupValues[1]); if (n.isNotBlank()) return Command.OpenApp(n)
        }
        return null
    }

    private val SEARCH_CMD = r("^(?:search|google|look\\s*up|find|dhoondo|search\\s+for)\\s+(?:for\\s+)?(.+)$")
    private val QUESTION = r("^(?:what|who|when|where|why|how|which|kya|kaun|kab|kahan|kaise)\\b")
    private fun search(t: String): Command? {
        SEARCH_CMD.find(t)?.let { m ->
            val q = tidy(m.groupValues[1]); if (q.isNotBlank()) return Command.Search(q)
        }
        if (QUESTION.containsMatchIn(t) && t.split(" ").size >= 3) return Command.Search(t)
        return null
    }

    private val VERBS = r("\\b(?:set|put|lagao|laga\\s*do|lag\\s*jaye|make|create|start|karo|kar\\s*do|banao|a|an|the|for|up)\\b")
    private fun stripVerbs(s: String) = VERBS.replace(s, " ").replace(Regex("\\s+"), " ").trim()
}
