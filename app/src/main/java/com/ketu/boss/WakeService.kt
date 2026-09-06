package com.ketu.boss

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ketu.boss.Prefs.listenMode
import com.ketu.boss.Prefs.listening
import com.ketu.boss.Prefs.isPaused
import com.ketu.boss.Prefs.pausedUntil
import com.ketu.boss.Prefs.sensitivity
import com.ketu.boss.Prefs.spokenWakeWord
import com.ketu.boss.Prefs.wakePhrases
import com.ketu.boss.Prefs.addHeard
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Holds the microphone and waits for the wake word.
 *
 * Runs the offline recogniser in *grammar* mode: the decoder only knows the
 * wake phrases plus "anything else", which is far cheaper than full
 * transcription and is what keeps this viable as an all-day service.
 */
class WakeService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "BossWake"
        const val ACTION_START = "com.ketu.boss.START"
        const val ACTION_STOP = "com.ketu.boss.STOP"
        const val ACTION_RESUME_LISTENING = "com.ketu.boss.RESUME"
        const val ACTION_LISTEN_NOW = "com.ketu.boss.LISTEN_NOW"
        const val ACTION_PAUSE_1H = "com.ketu.boss.PAUSE_1H"
        const val ACTION_TEST_WAKE = "com.ketu.boss.TEST_WAKE"
        const val EXTRA_WAS_CANCELLED = "was_cancelled"
        const val NOTIF_ID = 41

        /** Broadcast so the UI can show what the service is doing. */
        const val BROADCAST_STATE = "com.ketu.boss.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"

        @Volatile var state: String = "off"; private set
        /** True only while the service really holds a foreground slot. */
        @Volatile var alive: Boolean = false; private set
        @Volatile var detail: String = ""; private set

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
                .onFailure { Log.w(TAG, "could not start service", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_STOP)) }
        }

        @JvmOverloads
        fun resumeListening(ctx: Context, wasCancelled: Boolean = false) {
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_RESUME_LISTENING)
                .putExtra(EXTRA_WAS_CANCELLED, wasCancelled)
            // startService alone is refused from the background on O+; the
            // foreground variant is the one that survives the pop-up closing.
            runCatching { ContextCompat.startForegroundService(ctx, i) }
                .recoverCatching { ctx.startService(i) }
                .onFailure { Log.w(TAG, "could not resume listening", it) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    private var triggered = false
    private var lastTriggerAt = 0L
    private var lastLogged = ""
    /** Grows after a wake the user threw away, so a bad patch cannot nag. */
    private var cooldownMs = 2500L
    private var stopping = false
    private var foregroundOk = false

    /** Set when the model refused a grammar; we then transcribe and fuzzy-match. */
    private var freeFormFallback = false

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        BossApp.createChannels(this)
        worker = HandlerThread("boss-vosk").apply { start() }

        // Update checks were only ever armed from MainActivity.onResume, so an
        // app that is never opened relied entirely on one periodic job that
        // Samsung's battery manager is free to defer. The listening service
        // runs all day; let it keep the schedule alive too.
        runCatching {
            com.ketu.boss.update.UpdateWorker.schedule(this)
            com.ketu.boss.update.UpdateWorker.checkSoon(this, 3 * 60 * 60_000L)
        }
        bg = Handler(worker.looper)
        registerReceiver(gateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android wants the notification up within a few seconds of the start
        // request, before any of the slow work begins.
        //
        // From Android 14 a microphone-typed foreground service may only be
        // started while the app is genuinely in the foreground — a reboot, or
        // a restart after the process was killed, is refused. That refusal is
        // a SecurityException that would otherwise take the whole process
        // down, so it is caught and turned into a tap-to-resume notification.
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.app_name), "Starting…"))
        } catch (t: Throwable) {
            Log.w(TAG, "foreground start refused", t)
            Diagnostics.report(this, "fgs_refused", t.toString(), force = true)
            foregroundOk = false
            setStateNoNotify("blocked", "Android blocked the mic — open Boss once to resume")
            Notify.tapToResume(this)
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundOk = true
        alive = true
        Notify.clearResume(this)

        when (intent?.action) {
            ACTION_STOP -> { shutdownEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE_1H -> {
                pausedUntil = System.currentTimeMillis() + 3_600_000L
                stopRecognition()
                setState("paused", "Paused for an hour")
                return START_STICKY
            }
            ACTION_LISTEN_NOW -> {
                stopRecognition()
                triggered = true
                launchCommandUi(null)
                return START_STICKY
            }
            // Fires the real wake path after a delay, so the phone can be
            // locked first. The only honest way to check that a locked screen
            // actually lights up.
            ACTION_TEST_WAKE -> {
                setState("listening", "Test wake in 5 seconds — lock the phone now")
                main.postDelayed({
                    stopRecognition()
                    triggered = true
                    lastTriggerAt = System.currentTimeMillis()
                    Speaker.chime(this)
                    Speaker.vibrate(this)
                    setState("heard", "Test wake")
                    launchCommandUi(null)
                }, 5000)
                return START_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                if (intent.getBooleanExtra(EXTRA_WAS_CANCELLED, false)) {
                    cooldownMs = (cooldownMs * 2).coerceAtMost(30_000L)
                    Log.i(TAG, "wake was dismissed; cooldown now ${cooldownMs}ms")
                } else {
                    cooldownMs = 2500L
                }
                triggered = false
                main.postDelayed({ ensureRunning() }, 350)
                return START_STICKY
            }
        }

        if (!listening) listening = true
        ensureRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        shutdownEverything()
        runCatching { unregisterReceiver(gateReceiver) }
        runCatching { worker.quitSafely() }
        setState("off", "")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- listening gate ----------

    private val gateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!triggered) ensureRunning()
        }
    }

    private fun screenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun charging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun shouldListen(): Boolean {
        if (!listening) return false
        if (isPaused()) return false
        if (triggered) return false
        return when (listenMode) {
            Prefs.MODE_SCREEN_ON -> screenOn()
            Prefs.MODE_CHARGING -> charging()
            else -> true
        }
    }

    private fun gateReason(): String = when {
        isPaused() -> "Paused"
        listenMode == Prefs.MODE_SCREEN_ON -> "Waiting for the screen to come on"
        listenMode == Prefs.MODE_CHARGING -> "Waiting for the charger"
        else -> "Idle"
    }

    // ---------- recognition ----------

    private fun ensureRunning() {
        if (stopping) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setState("error", "Microphone permission is off")
            return
        }
        if (!shouldListen()) { stopRecognition(); setState("idle", gateReason()); return }
        if (speech != null) return

        setState("loading", if (model == null) "Getting the speech model ready…" else "Starting…")
        bg.post {
            try {
                val m = model ?: VoskEngine.load(this) { p -> main.post { setState("loading", p) } }
                model = m
                main.post { startRecognizer(m) }
            } catch (t: Throwable) {
                Log.e(TAG, "model load failed", t)
                Diagnostics.report(this, "model_failed", t.toString(), force = true)
                main.post { setState("error", "Speech model failed to load") }
            }
        }
    }

    private fun startRecognizer(m: Model) {
        if (!shouldListen() || speech != null) return
        try {
            val phrases = wakePhrases
            val rec = try {
                freeFormFallback = false
                Recognizer(m, 16000f, WakeMatcher.grammarJson(phrases))
            } catch (t: Throwable) {
                // A word outside the model's lexicon kills grammar mode. Fall
                // back to full transcription plus fuzzy matching — heavier, but
                // it still works, which matters more.
                Log.w(TAG, "grammar rejected, using free-form", t)
                freeFormFallback = true
                Recognizer(m, 16000f)
            }
            // Per-word confidence is the only real defence against grammar
            // mode over-firing: the decoder MUST pick either a wake phrase or
            // [unk], so on unclear audio it will guess a phrase.
            runCatching { rec.setWords(true) }
            recognizer = rec
            val svc = SpeechService(rec, 16000f)
            speech = svc
            svc.startListening(this)
            setState("listening", getString(R.string.listening_for, spokenWakeWord))
        } catch (t: Throwable) {
            Log.e(TAG, "could not start listening", t)
            Diagnostics.report(this, "mic_busy", t.toString())
            setState("error", "Microphone is busy")
            stopRecognition()
        }
    }

    private fun stopRecognition() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun shutdownEverything() {
        stopping = true
        stopRecognition()
        // The Model is deliberately kept: reloading costs seconds and it is
        // freed when the process dies anyway.
    }

    // ---------- vosk callbacks (main thread) ----------

    private fun textOf(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key, "") }.getOrDefault("")

    override fun onPartialResult(hypothesis: String?) {
        // Deliberately does NOT trigger. Partials in grammar mode flicker
        // through "hey boss" on their way to [unk], which fired the pop-up at
        // random. Only a settled result counts now.
        val t = textOf(hypothesis, "partial")
        if (t.isNotBlank() && t != "[unk]") setState("listening", "Hearing…")
    }

    override fun onResult(hypothesis: String?) {
        consider(textOf(hypothesis, "text"), confidence(hypothesis))
    }

    override fun onFinalResult(hypothesis: String?) {
        consider(textOf(hypothesis, "text"), confidence(hypothesis))
    }

    /** Mean per-word confidence of a final result, or 1.0 if unavailable. */
    private fun confidence(json: String?): Double = runCatching {
        val arr = JSONObject(json ?: "{}").optJSONArray("result") ?: return 1.0
        if (arr.length() == 0) return 1.0
        var sum = 0.0
        var n = 0
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            // [unk] carries no useful confidence of its own.
            if (w.optString("word") == "[unk]") continue
            sum += w.optDouble("conf", 1.0); n++
        }
        if (n == 0) 0.0 else sum / n
    }.getOrDefault(1.0)

    override fun onError(e: Exception?) {
        Log.e(TAG, "recognition error", e)
        Diagnostics.report(this, "recog_error", e?.toString())
        setState("error", e?.message ?: "Recognition error")
        stopRecognition()
        main.postDelayed({ ensureRunning() }, 2000)
    }

    override fun onTimeout() {
        stopRecognition()
        main.post { ensureRunning() }
    }

    private fun consider(heard: String, conf: Double) {
        if (triggered || heard.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < cooldownMs) return
        val phrases = wakePhrases
        val textMatches = WakeMatcher.matches(heard, phrases, sensitivity)

        // The wording has to have been heard clearly, not merely be the
        // decoder's least-bad option out of a three-phrase grammar.
        val minConf = when (sensitivity) {
            0 -> 0.55   // loose
            2 -> 0.90   // strict
            else -> 0.75
        }
        val hit = textMatches && conf >= minConf

        if (heard.length > 2 && heard != lastLogged) {
            lastLogged = heard
            val note = if (textMatches && !hit)
                heard + " (ignored, only " + Math.round(conf * 100) + "% sure)" else heard
            runCatching { addHeard(note, hit, screenOn()) }
        }
        if (!hit) return

        lastTriggerAt = now
        triggered = true
        // Anything said after the wake word in the same breath is passed
        // through, so "hey boss set an alarm for 6" works without a pause.
        val tail = WakeMatcher.tail(heard, phrases).takeIf { it.length > 2 }
        stopRecognition()
        Speaker.chime(this)
        Speaker.vibrate(this)
        setState("heard", "Listening…")
        launchCommandUi(tail)
    }

    private fun locked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked

    private fun launchCommandUi(prefill: String?) {
        val i = Intent(this, CommandActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(CommandActivity.EXTRA_PREFILL, prefill)

        // Hold the CPU across the hand-off. Without this the process can be
        // frozen again before the window is up.
        val wl = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boss:wake")
                .apply { acquire(15_000) }
        }.getOrNull()
        main.postDelayed({ runCatching { if (wl?.isHeld == true) wl.release() } }, 14_000)

        val awake = screenOn() && !locked()
        if (awake) {
            // startActivity from a service is SILENTLY dropped on Android 10+
            // when the app is not foreground — there is no exception to catch.
            // So try it, then check whether a window actually appeared.
            runCatching { startActivity(i) }
            main.postDelayed({
                if (triggered && !CommandActivity.showing) {
                    Log.w(TAG, "direct launch did not take, falling back to full-screen intent")
                    Diagnostics.report(this, "launch_blocked", "screen was on but no window appeared")
                    showFullScreenLauncher(i)
                }
            }, 1200)
        } else {
            // Screen off or locked: a full-screen intent is the only sanctioned
            // way to put a window up, and it is what turns the screen on.
            showFullScreenLauncher(i)
        }

        // Safety net: if the pop-up never reports back, resume anyway.
        main.postDelayed({ if (triggered) { triggered = false; ensureRunning() } }, 90_000)
    }

    private fun showFullScreenLauncher(target: Intent) {
        val pi = PendingIntent.getActivity(
            this, 7, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Android 14 can revoke the full-screen right. The notification still
        // lands, but it will not open anything and will not light the screen —
        // so the notification has to explain that itself, on the lock screen,
        // at the moment it fails.
        val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()

        val b = NotificationCompat.Builder(this, BossApp.CH_WAKE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Boss heard you")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)

        if (blocked) {
            Log.w(TAG, "full-screen intents are NOT permitted for this app")
            Diagnostics.report(this, "no_fullscreen", "canUseFullScreenIntent() is false", force = true)
            b.setContentText("Tap to speak — Android is blocking the screen from opening")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Tap to speak.\n\nThe screen could not open on its own: " +
                            "\"Full-screen notifications\" is switched off for Boss. " +
                            "Fix it in Boss \u2192 Setup."
                    )
                )
                .addAction(0, "Fix this", PendingIntent.getActivity(
                    this, 8,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
        } else {
            b.setContentText("Tap to speak")
        }

        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(42, b.build()) }
    }

    // ---------- notification ----------

    private fun pi(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        this, code,
        Intent(this, ServiceControlReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, BossApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Speak", pi(ACTION_LISTEN_NOW, 2))
            .addAction(0, "Pause 1h", pi(ACTION_PAUSE_1H, 3))
            .addAction(0, "Stop", pi(ACTION_STOP, 4))
            .build()
    }

    private fun setStateNoNotify(s: String, d: String) {
        state = s; detail = d
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, s).putExtra(EXTRA_DETAIL, d))
    }

    private fun setState(s: String, d: String) {
        state = s; detail = d
        if (!foregroundOk) { setStateNoNotify(s, d); return }
        val title = when (s) {
            "listening" -> "Boss is listening"
            "loading" -> "Boss is getting ready"
            "heard" -> "Boss heard you"
            "paused" -> "Boss is paused"
            "error" -> "Boss needs attention"
            "idle" -> "Boss is waiting"
            else -> getString(R.string.app_name)
        }
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(title, d))
        }
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, s).putExtra(EXTRA_DETAIL, d))
    }
}
