package com.ketu.boss

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ketu.boss.Prefs.bossId
import com.ketu.boss.Prefs.autoUpdate
import com.ketu.boss.Prefs.heardLog
import com.ketu.boss.Prefs.lastUpdateCheck
import com.ketu.boss.Prefs.history
import com.ketu.boss.Prefs.listenMode
import com.ketu.boss.Prefs.listening
import com.ketu.boss.Prefs.sensitivity
import com.ketu.boss.Prefs.spokenWakeWord
import com.ketu.boss.Prefs.wakePhrases
import com.ketu.boss.reminders.ReminderStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the phone can tell us about itself.
 *
 * This exists because the failures that matter here are invisible from the
 * outside: a wake word that is heard but cannot open a window, a service
 * Samsung quietly put to sleep, a permission Android revoked. The snapshot
 * answers those without needing the phone in hand.
 */
object Diagnostics {

    private const val TAG = "BossDiag"
    private val lastSent = HashMap<String, Long>()

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun snapshot(ctx: Context, kind: String, extra: String? = null): JSONObject {
        val o = JSONObject()
        runCatching {
            o.put("kind", kind)
            o.put("at", System.currentTimeMillis())
            o.put("device_id", ctx.bossId)
            extra?.let { o.put("note", it) }

            o.put("app", JSONObject().apply {
                put("version", BuildConfig.VERSION_NAME)
                put("code", BuildConfig.VERSION_CODE)
            })

            o.put("phone", JSONObject().apply {
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("manufacturer", Build.MANUFACTURER)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("fingerprint", Build.FINGERPRINT)
            })

            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nm = ctx.getSystemService(NotificationManager::class.java)

            o.put("permissions", JSONObject().apply {
                put("mic", granted(ctx, Manifest.permission.RECORD_AUDIO))
                put("contacts", granted(ctx, Manifest.permission.READ_CONTACTS))
                put("phone", granted(ctx, Manifest.permission.CALL_PHONE))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    put("post_notifications", granted(ctx, Manifest.permission.POST_NOTIFICATIONS))
                }
                put("notifications_enabled", NotificationManagerCompat.from(ctx).areNotificationsEnabled())
                put("overlay", Settings.canDrawOverlays(ctx))
                put("battery_unrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    put("exact_alarms", am.canScheduleExactAlarms())
                }
                // The one that decides whether a locked screen can light up.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    put("full_screen_intent", nm.canUseFullScreenIntent())
                }
                // Without this an update downloads and then cannot install.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    put("install_unknown_apps", ctx.packageManager.canRequestPackageInstalls())
                }
            })

            o.put("state", JSONObject().apply {
                put("listening_pref", ctx.listening)
                put("service_alive", WakeService.alive)
                put("service_state", WakeService.state)
                put("service_detail", WakeService.detail)
                put("listen_mode", ctx.listenMode)
                put("sensitivity", ctx.sensitivity)
                put("wake_word", ctx.spokenWakeWord)
                put("wake_phrases", JSONArray(ctx.wakePhrases))
                put("model_unpacked", VoskEngine.isUnpacked(ctx))
                put("screen_on", pm.isInteractive)
                put("locked", (ctx.getSystemService(Context.KEYGUARD_SERVICE)
                    as android.app.KeyguardManager).isKeyguardLocked)
                put("power_save", pm.isPowerSaveMode)
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                put("charging", bm.isCharging)
            })

            // The decisive evidence: what the wake engine actually decoded.
            // The two things that most plausibly kill a 50 MB download and
            // that I currently cannot see from here.
            o.put("network", runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val n = cm.activeNetwork
                val caps = n?.let { cm.getNetworkCapabilities(it) }
                JSONObject().apply {
                    put("kind", when {
                        caps == null -> "none"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    })
                    put("metered", caps == null || !caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    put("validated", caps?.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
                }
            }.getOrElse { JSONObject().put("error", it.toString()) })

            o.put("free_mb", runCatching {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: ctx.cacheDir
                android.os.StatFs(dir.absolutePath).availableBytes / (1024 * 1024)
            }.getOrDefault(-1L))

            o.put("update", JSONObject().apply {
                put("auto", ctx.autoUpdate)
                put("last_check", ctx.lastUpdateCheck)
                // Who Android thinks owns updates for this package decides
                // whether a silent self-update is permitted at all.
                put("installer", runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
                    else null
                }.getOrNull() ?: "unknown")
            })

            o.put("heard", JSONArray(ctx.heardLog()))
            o.put("commands", JSONArray(ctx.history().take(15)))
            o.put("reminders_pending", ReminderStore.pending(ctx).size)
        }.onFailure { o.put("snapshot_error", it.toString()) }
        return o
    }

    fun asText(ctx: Context, kind: String = "manual"): String =
        runCatching { snapshot(ctx, kind).toString(2) }.getOrElse { "could not build report: $it" }

    /**
     * Uploads a snapshot. Silent, off the main thread, and rate-limited per
     * kind so a repeating fault cannot turn into a flood.
     */
    fun report(ctx: Context, kind: String, extra: String? = null, force: Boolean = false) {
        val url = BuildConfig.BUG_URL
        val key = BuildConfig.BUG_KEY
        if (url.isBlank() || key.isBlank()) return

        val now = System.currentTimeMillis()
        synchronized(lastSent) {
            if (!force && now - (lastSent[kind] ?: 0L) < 10 * 60_000L) return
            lastSent[kind] = now
        }

        val app = ctx.applicationContext
        val payload = snapshot(app, kind, extra).toString()
        Thread {
            runCatching {
                val u = URL("$url/r?d=${app.bossId}&k=$kind")
                (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-boss-key", key)
                    OutputStreamWriter(outputStream).use { it.write(payload) }
                    Log.i(TAG, "report[$kind] -> $responseCode")
                    disconnect()
                }
            }.onFailure { Log.w(TAG, "report[$kind] failed", it) }
        }.start()
    }

    /** Crashes are the one thing that must never be rate-limited away. */
    fun installCrashHandler(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = Log.getStackTraceString(e).take(6000)
                val payload = snapshot(ctx, "crash", "thread=${t.name}\n$trace").toString()
                val url = BuildConfig.BUG_URL
                val key = BuildConfig.BUG_KEY
                if (url.isNotBlank() && key.isNotBlank()) {
                    // Synchronous on purpose: the process is about to die.
                    val u = URL("$url/r?d=${ctx.bossId}&k=crash")
                    (u.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 4000; readTimeout = 4000; doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("x-boss-key", key)
                        OutputStreamWriter(outputStream).use { it.write(payload) }
                        responseCode
                        disconnect()
                    }
                }
            }
            prev?.uncaughtException(t, e)
        }
    }
}
