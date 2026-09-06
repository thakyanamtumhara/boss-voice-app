package com.ketu.boss

import android.content.Context
import org.json.JSONArray

/**
 * Every setting lives here. Wake phrases are stored as a list because the
 * "teach" flow records what the recogniser *actually heard* three times over —
 * that is far more reliable than storing the phrase the way it is spelled.
 */
object Prefs {
    private const val FILE = "boss"

    private const val K_PHRASES = "wake_phrases"
    private const val K_SPOKEN = "wake_spoken"
    private const val K_LISTENING = "listening"
    private const val K_MODE = "listen_mode"
    private const val K_SPEAK = "speak_replies"
    private const val K_CONFIRM_MS = "confirm_ms"
    private const val K_CALL_CONFIRM = "call_confirm"
    private const val K_HISTORY = "history"
    private const val K_PAUSED_UNTIL = "paused_until"
    private const val K_SENSITIVITY = "sensitivity"

    /** Wake-word listening windows, so the mic need not run 24/7. */
    const val MODE_ALWAYS = "always"
    const val MODE_SCREEN_ON = "screen_on"
    const val MODE_CHARGING = "charging"

    val DEFAULT_PHRASES = listOf("hey boss", "ok boss", "hi boss")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.wakePhrases: List<String>
        get() {
            val raw = sp(this).getString(K_PHRASES, null) ?: return DEFAULT_PHRASES
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(DEFAULT_PHRASES).ifEmpty { DEFAULT_PHRASES }
        }
        set(v) {
            val a = JSONArray()
            v.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().forEach { a.put(it) }
            sp(this).edit().putString(K_PHRASES, a.toString()).apply()
        }

    /** What the wake word is *called* in the UI — what you meant to say. */
    var Context.spokenWakeWord: String
        get() = sp(this).getString(K_SPOKEN, null) ?: DEFAULT_PHRASES.first()
        set(v) = sp(this).edit().putString(K_SPOKEN, v.trim()).apply()

    var Context.listening: Boolean
        get() = sp(this).getBoolean(K_LISTENING, false)
        set(v) = sp(this).edit().putBoolean(K_LISTENING, v).apply()

    var Context.listenMode: String
        get() = sp(this).getString(K_MODE, MODE_ALWAYS) ?: MODE_ALWAYS
        set(v) = sp(this).edit().putString(K_MODE, v).apply()

    var Context.speakReplies: Boolean
        get() = sp(this).getBoolean(K_SPEAK, true)
        set(v) = sp(this).edit().putBoolean(K_SPEAK, v).apply()

    /** Grace period before a parsed command runs itself. 0 = run at once. */
    var Context.confirmMillis: Int
        get() = sp(this).getInt(K_CONFIRM_MS, 2500)
        set(v) = sp(this).edit().putInt(K_CONFIRM_MS, v).apply()

    /** A misheard name dialling the wrong person is the one unrecoverable
     *  mistake here, so calls always ask by default. */
    var Context.callNeedsConfirm: Boolean
        get() = sp(this).getBoolean(K_CALL_CONFIRM, true)
        set(v) = sp(this).edit().putBoolean(K_CALL_CONFIRM, v).apply()

    /** 0 = loosest match, 2 = strictest. Trades false wakes against misses. */
    var Context.sensitivity: Int
        get() = sp(this).getInt(K_SENSITIVITY, 1)
        set(v) = sp(this).edit().putInt(K_SENSITIVITY, v.coerceIn(0, 2)).apply()

    var Context.pausedUntil: Long
        get() = sp(this).getLong(K_PAUSED_UNTIL, 0L)
        set(v) = sp(this).edit().putLong(K_PAUSED_UNTIL, v).apply()

    fun Context.isPaused(): Boolean = pausedUntil > System.currentTimeMillis()

    fun Context.history(): List<String> {
        val raw = sp(this).getString(K_HISTORY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun Context.addHistory(line: String) {
        val next = (listOf("${System.currentTimeMillis()}|$line") + history()).take(40)
        val a = JSONArray()
        next.forEach { a.put(it) }
        sp(this).edit().putString(K_HISTORY, a.toString()).apply()
    }

    fun Context.clearHistory() = sp(this).edit().remove(K_HISTORY).apply()
}
