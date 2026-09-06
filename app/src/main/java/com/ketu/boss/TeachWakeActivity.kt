package com.ketu.boss

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ketu.boss.Prefs.listening
import com.ketu.boss.Prefs.spokenWakeWord
import com.ketu.boss.Prefs.wakePhrases
import com.ketu.boss.databinding.ActivityTeachBinding
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Teaches a wake word by recording what the offline recogniser *hears*, three
 * times over. That is what makes an out-of-vocabulary word like a name usable:
 * we never need the model to spell it, only to be consistent about it.
 */
class TeachWakeActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var b: ActivityTeachBinding
    private val main = Handler(Looper.getMainLooper())

    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private val captures = mutableListOf<String>()
    private var recording = false
    private var stopTimer: Runnable? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginCapture() else b.hint.text = "Microphone permission is needed to learn the word."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTeachBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.name.setText(spokenWakeWord)
        // The listening service owns the microphone; it has to let go first.
        WakeService.stop(this)

        b.record.setOnClickListener {
            if (recording) return@setOnClickListener
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) askMic.launch(Manifest.permission.RECORD_AUDIO) else beginCapture()
        }
        b.reset.setOnClickListener {
            captures.clear(); renderCaptures(); b.hint.text = ""
            b.record.text = "Say it now (1 of 3)"
        }
        b.save.setOnClickListener { save() }
        renderCaptures()
    }

    // ---------- capture ----------

    private fun beginCapture() {
        recording = true
        b.hint.text = "Listening — say it now."
        b.record.isEnabled = false
        Speaker.chime(this)

        Thread {
            try {
                val model = VoskEngine.load(this) { p -> main.post { b.hint.text = p } }
                main.post {
                    try {
                        // Free-form here on purpose: we want the model's honest
                        // transcription, whatever it is.
                        val rec = Recognizer(model, 16000f)
                        recognizer = rec
                        val svc = SpeechService(rec, 16000f)
                        speech = svc
                        svc.startListening(this)
                        stopTimer = Runnable { endCapture() }
                        main.postDelayed(stopTimer!!, 3500)
                    } catch (t: Throwable) {
                        fail(t)
                    }
                }
            } catch (t: Throwable) {
                main.post { fail(t) }
            }
        }.start()
    }

    private fun fail(t: Throwable) {
        recording = false
        b.record.isEnabled = true
        b.hint.text = "Couldn't use the microphone: " + (t.message ?: "unknown error")
    }

    private fun endCapture() {
        stopTimer?.let { main.removeCallbacks(it) }; stopTimer = null
        val heard = runCatching { recognizer?.finalResult }.getOrNull()
        release()
        val text = textOf(heard)
        recording = false
        b.record.isEnabled = true
        if (text.isBlank()) {
            b.hint.text = "Didn't hear anything — try again, a bit louder."
        } else {
            captures.add(text)
            b.hint.text = "Heard: “$text”"
        }
        b.record.text = "Say it again (${(captures.size + 1).coerceAtMost(3)} of 3)"
        if (captures.size >= 3) {
            b.record.text = "Say it once more (optional)"
            b.hint.text = "Got it. Tap Save."
        }
        renderCaptures()
    }

    private fun release() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun textOf(json: String?): String =
        runCatching { JSONObject(json ?: "{}").optString("text", "") }.getOrDefault("").trim()

    private fun renderCaptures() {
        b.captures.removeAllViews()
        if (captures.isEmpty()) {
            b.captures.addView(TextView(this).apply {
                text = "Nothing recorded yet."
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_lo))
            })
            return
        }
        captures.forEachIndexed { i, c ->
            b.captures.addView(TextView(this).apply {
                text = "${i + 1}.  “$c”"
                textSize = 15f
                setPadding(0, 8, 0, 8)
                setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            })
        }
    }

    // ---------- save ----------

    private fun save() {
        val typed = b.name.text.toString().trim()
        val heard = captures.map { WakeMatcher.normalize(it) }.filter { it.isNotBlank() }.distinct()

        // Grammar entries must be words the model can actually decode, so they
        // come from what it heard. The typed text is only a fallback.
        val phrases = when {
            heard.isNotEmpty() -> heard
            typed.isNotEmpty() -> listOf(WakeMatcher.normalize(typed))
            else -> emptyList()
        }
        if (phrases.isEmpty()) {
            b.hint.text = "Record the word at least once, or type it above."
            return
        }
        wakePhrases = phrases
        spokenWakeWord = typed.ifBlank { heard.first() }
        b.hint.text = "Saved. Boss now wakes on “${spokenWakeWord}”."
        if (listening) WakeService.start(this)
        main.postDelayed({ finish() }, 900)
    }

    override fun onDestroy() {
        release()
        if (listening) WakeService.start(this)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ---------- vosk ----------

    override fun onPartialResult(hypothesis: String?) {
        val t = runCatching { JSONObject(hypothesis ?: "{}").optString("partial", "") }.getOrDefault("")
        if (t.isNotBlank()) b.hint.text = "Hearing: “$t”"
    }

    override fun onResult(hypothesis: String?) {
        val t = textOf(hypothesis)
        if (t.isNotBlank() && recording) {
            stopTimer?.let { main.removeCallbacks(it) }
            captures.add(t)
            release()
            recording = false
            b.record.isEnabled = true
            b.hint.text = "Heard: “$t”"
            b.record.text = if (captures.size >= 3) "Say it once more (optional)"
                else "Say it again (${captures.size + 1} of 3)"
            renderCaptures()
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(e: Exception?) { main.post { fail(e ?: RuntimeException("error")) } }
    override fun onTimeout() { main.post { endCapture() } }
}
