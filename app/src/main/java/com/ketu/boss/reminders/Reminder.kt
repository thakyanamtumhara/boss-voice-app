package com.ketu.boss.reminders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    val atMillis: Long,
    val text: String,
    val done: Boolean = false,
    /** Rings as an alarm rather than a reminder — set while the phone was locked. */
    val isAlarm: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("at", atMillis).put("text", text).put("done", done).put("alarm", isAlarm)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            o.optInt("id"), o.optLong("at"), o.optString("text"),
            o.optBoolean("done", false), o.optBoolean("alarm", false)
        )
    }
}

/**
 * Reminders live in SharedPreferences, not the calendar: they have to be able
 * to ring loudly, survive a reboot, and be cancelled from inside the app.
 */
object ReminderStore {
    private const val FILE = "boss_reminders"
    private const val KEY = "items"
    private const val KEY_SEQ = "seq"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Reminder.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedBy { it.atMillis }
    }

    fun pending(ctx: Context): List<Reminder> =
        all(ctx).filter { !it.done && it.atMillis > System.currentTimeMillis() - 60_000 }

    private fun save(ctx: Context, items: List<Reminder>) {
        // Keep the last 100 so the file cannot grow without bound.
        val trimmed = items.sortedByDescending { it.atMillis }.take(100)
        val a = JSONArray()
        trimmed.forEach { a.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, a.toString()).apply()
    }

    fun add(ctx: Context, atMillis: Long, text: String, isAlarm: Boolean = false): Reminder {
        val seq = sp(ctx).getInt(KEY_SEQ, 1000) + 1
        sp(ctx).edit().putInt(KEY_SEQ, seq).apply()
        val r = Reminder(seq, atMillis, text, isAlarm = isAlarm)
        save(ctx, all(ctx) + r)
        return r
    }

    fun markDone(ctx: Context, id: Int) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(done = true) else it })
    }

    fun remove(ctx: Context, id: Int) {
        save(ctx, all(ctx).filterNot { it.id == id })
    }

    fun get(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }
}
