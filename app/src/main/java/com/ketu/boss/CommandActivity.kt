package com.ketu.boss

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ketu.boss.Prefs.addHistory
import com.ketu.boss.Prefs.callNeedsConfirm
import com.ketu.boss.Prefs.confirmMillis
import com.ketu.boss.Prefs.speakReplies
import com.ketu.boss.act.ActionRunner
import com.ketu.boss.act.Match
import com.ketu.boss.act.Outcome
import com.ketu.boss.databinding.ActivityCommandBinding
import com.ketu.boss.parse.Command
import com.ketu.boss.parse.CommandParser

/**
 * The pop-up that appears after the wake word: hear the command, show what it
 * was understood as, then do it. Every path ends by handing the microphone
 * back to [WakeService].
 */
class CommandActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL = "prefill"
        private const val TAG = "BossCmd"

        /**
         * Whether a pop-up is actually on screen. [WakeService] needs this
         * because a blocked background activity launch throws nothing — the
         * only way to know it failed is that no window appeared.
         */
        @Volatile var showing: Boolean = false; private set
    }

    private lateinit var b: ActivityCommandBinding
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var pending: Command? = null
    private var countdown: Runnable? = null
    private var finished = false
    private var triedOffline = false

    /** Set when we asked a follow-up ("when?") and are waiting for the answer. */
    private var awaiting: Command.NeedTime? = null
    private var watchdog: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        b = ActivityCommandBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.version.text = "v" + BuildConfig.VERSION_NAME
        Speaker.init(this)

        b.cancel.setOnClickListener { say(""); done() }
        b.scrim.setOnClickListener { done() }
        b.retry.setOnClickListener { restartListening() }
        b.go.setOnClickListener { pending?.let { c -> cancelCountdown(); execute(c) } }
        b.send.setOnClickListener {
            val t = b.typed.text.toString().trim()
            if (t.isNotEmpty()) handleTranscript(t)
        }
        b.typed.setOnEditorActionListener { _, _, _ ->
            val t = b.typed.text.toString().trim()
            if (t.isNotEmpty()) handleTranscript(t); true
        }

        val prefill = intent?.getStringExtra(EXTRA_PREFILL)?.trim()
        if (!prefill.isNullOrEmpty()) {
            b.heard.text = prefill
            handleTranscript(prefill)
        } else {
            startListening()
        }
    }

    override fun onStart() { super.onStart(); showing = true }

    override fun onStop() { showing = false; super.onStop() }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        restartListening()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            // Deliberately NOT dismissing the keyguard here. Doing it on launch
            // made the phone demand a PIN before it would even listen — and
            // most of what gets asked for (alarm, reminder, timer, torch, the
            // time) needs no unlocking at all. The prompt is deferred to the
            // one action that actually cannot proceed without it.
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- listening ----------

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state("Voice input unavailable")
            b.heard.text = "Type the command instead."
            b.typeRow.visibility = View.VISIBLE
            return
        }
        releaseRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (triedOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        state(if (awaiting != null) awaiting!!.title else "Listening…")
        runCatching { r.startListening(i) }.onFailure {
            state("Couldn't open the microphone")
            b.typeRow.visibility = View.VISIBLE
        }
        // Some recognisers neither return a result nor report an error. Put
        // the keyboard within reach rather than leaving a dead pop-up.
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = Runnable {
            if (pending == null && b.typeRow.visibility != View.VISIBLE) {
                b.typeRow.visibility = View.VISIBLE
            }
        }
        main.postDelayed(watchdog!!, 9000)
    }

    private fun restartListening() {
        pending = null
        cancelCountdown()
        b.go.visibility = View.GONE
        b.interpretation.visibility = View.GONE
        b.choices.removeAllViews()
        b.heard.text = "…"
        startListening()
    }

    private fun releaseRecognizer() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { state("Listening…") }
        override fun onBeginningOfSpeech() { state("Go ahead…") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { state("Thinking…") }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
            if (!t.isNullOrEmpty()) b.heard.text = t
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (t.isEmpty()) { state("Didn't catch that"); b.typeRow.visibility = View.VISIBLE; return }
            b.heard.text = t
            handleTranscript(t)
        }

        override fun onError(error: Int) {
            // A network failure is worth one silent retry on the offline
            // engine before giving up and offering the keyboard.
            if ((error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) && !triedOffline
            ) {
                triedOffline = true
                main.postDelayed({ startListening() }, 150)
                return
            }
            state(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy — try again"
                    else -> "Couldn't hear you"
                }
            )
            b.typeRow.visibility = View.VISIBLE
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- understanding ----------

    private fun handleTranscript(raw: String) {
        cancelWatchdog()
        releaseRecognizer()
        b.heard.text = raw
        b.typeRow.visibility = View.GONE
        b.typed.setText("")

        // A follow-up answer gets glued onto the question it answered.
        val pendingAsk = awaiting
        val text = if (pendingAsk == null) raw else when (pendingAsk.kind) {
            Command.NeedTime.Kind.ALARM -> "set an alarm $raw"
            Command.NeedTime.Kind.TIMER -> "set a timer $raw"
            Command.NeedTime.Kind.REMINDER ->
                "remind me $raw" + (pendingAsk.carry?.let { " to $it" } ?: "")
            Command.NeedTime.Kind.CALL_WHO -> "call $raw"
        }
        awaiting = null

        val cmd = CommandParser.parse(text)
        when (cmd) {
            is Command.Cancel -> { say(""); done() }
            is Command.NeedTime -> ask(cmd)
            is Command.Unknown -> offerSearch(cmd)
            else -> propose(cmd)
        }
    }

    private fun ask(c: Command.NeedTime) {
        awaiting = c
        b.interpretation.visibility = View.VISIBLE
        b.interpretation.text = c.title
        state(c.title)
        say(c.spoken) { main.post { startListening() } }
    }

    private fun offerSearch(c: Command.Unknown) {
        state("Not sure what that was")
        b.interpretation.visibility = View.VISIBLE
        b.interpretation.text = "Search the web for it?"
        b.typeRow.visibility = View.VISIBLE
        pending = Command.Search(c.raw)
        b.go.visibility = if (c.raw.isBlank()) View.GONE else View.VISIBLE
        b.go.text = "Search"
    }

    private fun propose(c: Command) {
        pending = c
        b.interpretation.visibility = View.VISIBLE
        b.interpretation.text = c.title

        val mustAsk = c.needsConfirm && callNeedsConfirm
        val delay = confirmMillis
        if (mustAsk) {
            state("Confirm?")
            b.go.visibility = View.VISIBLE
            b.go.text = "Call"
            return
        }
        if (delay <= 0) { execute(c); return }
        b.go.visibility = View.VISIBLE
        b.go.text = "Do it"
        state("Doing this…")
        countdown = Runnable { execute(c) }
        main.postDelayed(countdown!!, delay.toLong())
    }

    private fun cancelCountdown() {
        countdown?.let { main.removeCallbacks(it) }
        countdown = null
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    // ---------- doing ----------

    private fun execute(c: Command) {
        cancelCountdown()
        b.go.visibility = View.GONE

        // Only now, and only if this particular command cannot work behind a
        // lock screen, ask for the PIN — so the prompt is always attached to
        // something that needed it.
        if (c.needsUnlock && isLocked()) {
            state("Unlock to do this")
            runCatching {
                (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager)
                    .requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() { main.post { runAction(c) } }
                        override fun onDismissError() { main.post { runAction(c) } }
                        override fun onDismissCancelled() {
                            main.post { state("Cancelled"); done() }
                        }
                    })
            }.onFailure { runAction(c) }
            return
        }
        runAction(c)
    }

    private fun isLocked(): Boolean = runCatching {
        (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)

    private fun runAction(c: Command) {
        val out: Outcome = ActionRunner.run(this, c)
        addHistory((if (out.ok) "OK|" else "NO|") + c.title)

        if (!out.ok && out.choices != null) { chooseContact(out, c); return }

        state(if (out.ok) "Done" else "Couldn't do it")
        b.interpretation.text = out.detail ?: out.say
        if (!out.ok) b.interpretation.text = out.say + (out.detail?.let { "\n$it" } ?: "")
        // Always leave a note. The pop-up often disappears behind whatever app
        // the action opened, so the notification is the lasting proof.
        val note = (out.detail ?: out.say).trim()
        if (note.isNotEmpty()) {
            Notify.said(
                this,
                (if (out.ok) "✓ " else "× ") + note,
                "heard: \"" + b.heard.text + "\""
            )
        }
        say(out.say) { done() }
        // Never leave the pop-up sitting there if speech is off or fails.
        main.postDelayed({ done() }, if (out.ok) 1800 else 3200)
    }

    /** Two contacts scored the same, so ask instead of dialling a guess. */
    private fun chooseContact(out: Outcome, original: Command) {
        state("Which one?")
        b.interpretation.visibility = View.VISIBLE
        b.interpretation.text = out.say
        b.choices.removeAllViews()
        out.choices?.take(4)?.forEach { m: Match ->
            val btn = Button(this).apply {
                text = "${m.name}  ·  ${m.number}"
                isAllCaps = false
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(resources.getColor(R.color.text_hi, theme))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                setOnClickListener {
                    b.choices.removeAllViews()
                    val next = when (original) {
                        is Command.Call -> Command.Call(m.number)
                        is Command.Message -> Command.Message(m.number, original.body)
                        else -> original
                    }
                    execute(next)
                }
            }
            b.choices.addView(btn)
        }
        main.postDelayed({ done() }, 20_000)
    }

    // ---------- helpers ----------

    private fun state(s: String) { b.state.text = s }

    private fun say(text: String, then: (() -> Unit)? = null) {
        if (text.isBlank() || !speakReplies) { then?.invoke(); return }
        Speaker.speak(this, text) { main.post { then?.invoke() } }
    }

    private fun done() {
        if (finished) return
        finished = true
        cancelCountdown()
        cancelWatchdog()
        releaseRecognizer()
        WakeService.resumeListening(this)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen must not strand the microphone.
        if (!isChangingConfigurations) done()
    }

    override fun onDestroy() {
        releaseRecognizer()
        if (!finished) WakeService.resumeListening(this)
        super.onDestroy()
    }
}
