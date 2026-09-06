package com.ketu.boss

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken confirmations plus the little "I'm listening" chime.
 *
 * Speech goes out on the ASSISTANT/notification stream rather than the media
 * stream, so it does not duck or fight whatever is playing.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: MutableList<Pair<String, (() -> Unit)?>> = mutableListOf()

    fun init(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale("en", "IN").takeIf {
                    tts?.isLanguageAvailable(it) ?: 0 >= TextToSpeech.LANG_AVAILABLE
                } ?: Locale.UK
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val queued = pending.toList(); pending.clear()
                queued.forEach { (t, done) -> speak(app, t, done) }
            } else {
                val queued = pending.toList(); pending.clear()
                queued.forEach { (_, done) -> done?.invoke() }
            }
        }
    }

    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) { onDone?.invoke(); return }
        init(ctx)
        val engine = tts
        if (engine == null || !ready) { pending.add(text to onDone); return }
        val id = "boss-" + System.nanoTime()
        if (onDone != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone() }
                @Deprecated("legacy") override fun onError(utteranceId: String?) { if (utteranceId == id) onDone() }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) onDone() }
            })
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { runCatching { tts?.stop() } }

    /** Two quick rising blips: "I heard you, go ahead." */
    @Suppress("UNUSED_PARAMETER")
    fun chime(ctx: Context) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { tg.release() }
            }, 400)
        }
    }

    fun vibrate(ctx: Context, ms: Long = 40) {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ready = false; pending.clear()
    }
}
