package com.ketu.boss.parse

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** What Boss decided you asked for. [title] is shown, [spoken] is said aloud. */
sealed class Command {
    abstract val title: String
    abstract val spoken: String

    /** True for anything that is awkward to undo — calls, mainly. */
    open val needsConfirm: Boolean get() = false

    /**
     * True only when the action genuinely cannot happen behind a lock screen.
     * Alarms, timers and reminders deliberately are NOT in this set — asking
     * for a PIN to set a 6:30 alarm defeats the point of saying it out loud.
     */
    open val needsUnlock: Boolean get() = false

    data class Alarm(val hour: Int, val minute: Int, val atMillis: Long, val label: String?) : Command() {
        override val title = "Alarm · " + Fmt.clockAndDay(atMillis) + (label?.let { " · $it" } ?: "")
        override val spoken = "Alarm set for " + Fmt.spokenClock(atMillis) + Fmt.spokenDay(atMillis)
    }

    data class Timer(val seconds: Int, val label: String?) : Command() {
        override val title = "Timer · " + Fmt.duration(seconds) + (label?.let { " · $it" } ?: "")
        override val spoken = "Timer set for " + Fmt.duration(seconds)
    }

    data class Reminder(val atMillis: Long, val text: String) : Command() {
        override val title = "Reminder · " + Fmt.clockAndDay(atMillis) + " · " + text
        override val spoken = "I'll remind you at " + Fmt.spokenClock(atMillis) + Fmt.spokenDay(atMillis) + " to " + text
    }

    data class Call(val who: String) : Command() {
        override val title = "Call $who"
        override val spoken = "Calling $who"
        override val needsConfirm = true
    }

    data class Message(val who: String, val body: String?) : Command() {
        override val title = "WhatsApp $who" + (body?.let { ": $it" } ?: "")
        override val spoken = "Opening WhatsApp for $who"
        override val needsUnlock = true
    }

    data class OpenApp(val name: String) : Command() {
        override val title = "Open $name"
        override val spoken = "Opening $name"
        override val needsUnlock = true
    }

    data class Navigate(val place: String) : Command() {
        override val title = "Directions to $place"
        override val spoken = "Getting directions to $place"
        override val needsUnlock = true
    }

    data class Play(val query: String) : Command() {
        override val title = "Play $query"
        override val spoken = "Playing $query"
        override val needsUnlock = true
    }

    data class Search(val query: String) : Command() {
        override val title = "Search: $query"
        override val spoken = "Searching for $query"
        override val needsUnlock = true
    }

    data class Torch(val on: Boolean) : Command() {
        override val title = if (on) "Torch on" else "Torch off"
        override val spoken = title
    }

    data class Volume(val kind: Kind, val level: Int? = null) : Command() {
        enum class Kind { UP, DOWN, MUTE, SET, SILENT, LOUD }
        override val title = when (kind) {
            Kind.UP -> "Volume up"; Kind.DOWN -> "Volume down"; Kind.MUTE -> "Mute"
            Kind.SILENT -> "Silent mode"; Kind.LOUD -> "Ringer on"; Kind.SET -> "Volume ${level ?: 0}%"
        }
        override val spoken = title
    }

    /**
     * Understood the verb but not the detail — "remind me to call the CA" with
     * no time. The UI asks one follow-up question instead of guessing.
     */
    data class NeedTime(val kind: Kind, val carry: String?) : Command() {
        enum class Kind { ALARM, TIMER, REMINDER, CALL_WHO }
        override val title = when (kind) {
            Kind.ALARM -> "Alarm — what time?"
            Kind.TIMER -> "Timer — how long?"
            Kind.REMINDER -> "Reminder — when?"
            Kind.CALL_WHO -> "Call — who?"
        }
        override val spoken = when (kind) {
            Kind.ALARM -> "What time?"
            Kind.TIMER -> "How long?"
            Kind.REMINDER -> "When should I remind you?"
            Kind.CALL_WHO -> "Who should I call?"
        }
    }

    object TimeNow : Command() {
        override val title = "What's the time"
        override val spoken = ""     // filled in at run time
    }

    object BatteryNow : Command() {
        override val title = "Battery level"
        override val spoken = ""
    }

    object ShowReminders : Command() {
        override val title = "Your reminders"
        override val spoken = ""
    }

    object Cancel : Command() {
        override val title = "Cancelled"
        override val spoken = ""
    }

    data class Unknown(val raw: String) : Command() {
        override val title = if (raw.isBlank()) "Didn't catch that" else "Not sure: \"$raw\""
        override val spoken = ""
    }
}

/** Small, locale-fixed formatters shared by the command descriptions. */
object Fmt {
    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    private val clock12 = SimpleDateFormat("h:mm a", Locale.ENGLISH)
    private val clock12NoMin = SimpleDateFormat("h a", Locale.ENGLISH)
    private val dayName = SimpleDateFormat("EEEE", Locale.ENGLISH)
    private val dateShort = SimpleDateFormat("d MMM", Locale.ENGLISH)

    fun clock(ms: Long): String = clock12.format(ms)

    /** Days between today and [ms], in local calendar days. */
    private fun dayDelta(ms: Long): Int {
        val a = cal(System.currentTimeMillis()).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val b = cal(ms).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
    }

    fun clockAndDay(ms: Long): String = when (val d = dayDelta(ms)) {
        0 -> clock(ms) + " today"
        1 -> clock(ms) + " tomorrow"
        in 2..6 -> clock(ms) + " " + dayName.format(ms)
        else -> clock(ms) + " " + dateShort.format(ms) + (if (d < 0) " (past)" else "")
    }

    fun spokenClock(ms: Long): String {
        val c = cal(ms)
        return if (c.get(Calendar.MINUTE) == 0) clock12NoMin.format(ms) else clock12.format(ms)
    }

    fun spokenDay(ms: Long): String = when (dayDelta(ms)) {
        0 -> ""
        1 -> " tomorrow"
        in 2..6 -> " on " + dayName.format(ms)
        else -> " on " + dateShort.format(ms)
    }

    fun duration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val bits = buildList {
            if (h > 0) add("$h hour" + if (h > 1) "s" else "")
            if (m > 0) add("$m minute" + if (m > 1) "s" else "")
            if (s > 0 && h == 0) add("$s second" + if (s > 1) "s" else "")
        }
        return if (bits.isEmpty()) "0 seconds" else bits.joinToString(" ")
    }
}
