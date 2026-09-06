package com.ketu.boss.act

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ketu.boss.parse.Command
import com.ketu.boss.parse.Fmt
import com.ketu.boss.reminders.ReminderScheduler
import com.ketu.boss.reminders.ReminderStore
import java.util.Locale

/** The outcome of carrying out a command. */
data class Outcome(
    val ok: Boolean,
    val say: String,
    val detail: String? = null,
    /** Set when the app has to ask something before it can finish. */
    val choices: List<Match>? = null
)

object ActionRunner {

    fun run(ctx: Context, c: Command): Outcome = when (c) {
        is Command.Alarm -> alarm(ctx, c)
        is Command.Timer -> timer(ctx, c)
        is Command.Reminder -> reminder(ctx, c)
        is Command.Call -> call(ctx, c)
        is Command.Message -> message(ctx, c)
        is Command.OpenApp -> openApp(ctx, c)
        is Command.Navigate -> navigate(ctx, c.place)
        is Command.Play -> play(ctx, c.query)
        is Command.Search -> search(ctx, c.query)
        is Command.Torch -> torch(ctx, c.on)
        is Command.Volume -> volume(ctx, c)
        is Command.TimeNow -> Outcome(true, "It is " + Fmt.clock(System.currentTimeMillis()))
        is Command.BatteryNow -> battery(ctx)
        is Command.ShowReminders -> reminders(ctx)
        is Command.Cancel -> Outcome(true, "")
        is Command.NeedTime -> Outcome(false, c.spoken)
        is Command.Unknown -> Outcome(false, "I didn't get that")
    }

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false }

    // ---------- clock ----------

    private fun locked(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)

    private fun alarm(ctx: Context, c: Command.Alarm): Outcome {
        // Handing this to the Clock app means launching its activity, and
        // Android will demand the PIN first. Setting a 6:30 alarm from the
        // pillow is the whole point, so Boss rings it itself instead. Once
        // unlocked it goes to the real clock, where he can see and edit it.
        if (locked(ctx)) {
            val label = c.label?.takeIf { it.isNotBlank() } ?: "Alarm"
            val r = ReminderStore.add(ctx, c.atMillis, label, isAlarm = true)
            ReminderScheduler.schedule(ctx, r)
            return Outcome(true, c.spoken, c.title + " · Boss will ring it (phone was locked)")
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, c.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (launch(ctx, i)) Outcome(true, c.spoken, c.title)
        else Outcome(false, "No clock app would take that alarm")
    }

    private fun timer(ctx: Context, c: Command.Timer): Outcome {
        if (locked(ctx)) {
            val at = System.currentTimeMillis() + c.seconds * 1000L
            val r = ReminderStore.add(ctx, at, c.label?.takeIf { it.isNotBlank() } ?: "Timer", isAlarm = true)
            ReminderScheduler.schedule(ctx, r)
            return Outcome(true, c.spoken, c.title + " · Boss will ring it (phone was locked)")
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, c.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (launch(ctx, i)) Outcome(true, c.spoken, c.title)
        else Outcome(false, "No clock app would take that timer")
    }

    private fun reminder(ctx: Context, c: Command.Reminder): Outcome {
        val r = ReminderStore.add(ctx, c.atMillis, c.text)
        ReminderScheduler.schedule(ctx, r)
        return Outcome(true, c.spoken, c.title)
    }

    private fun reminders(ctx: Context): Outcome {
        val list = ReminderStore.pending(ctx)
        if (list.isEmpty()) return Outcome(true, "You have no reminders")
        val head = list.take(3).joinToString("; ") { "${it.text} at ${Fmt.clock(it.atMillis)}" }
        val more = if (list.size > 3) " and ${list.size - 3} more" else ""
        return Outcome(true, "You have ${list.size} reminder" + (if (list.size > 1) "s" else "") + ": $head$more",
            list.joinToString("\n") { "• ${Fmt.clockAndDay(it.atMillis)} — ${it.text}" })
    }

    // ---------- people ----------

    private fun call(ctx: Context, c: Command.Call): Outcome {
        val digitsOnly = c.who.count { it.isDigit() } >= 7 && c.who.none { it.isLetter() }
        val number: String
        val label: String
        if (digitsOnly) {
            number = c.who.filter { it.isDigit() || it == '+' }; label = number
        } else {
            if (!ContactFinder.hasPermission(ctx)) {
                return Outcome(false, "I need permission to read your contacts", "Grant Contacts access in the app")
            }
            val candidates = ContactFinder.search(ctx, c.who)
            val best = ContactFinder.best(ctx, c.who)
                ?: return Outcome(
                    false,
                    if (candidates.isEmpty()) "I couldn't find ${c.who} in your contacts" else "Which one?",
                    if (candidates.isEmpty()) null else "More than one match",
                    candidates.takeIf { it.isNotEmpty() }
                )
            number = best.number; label = best.name
        }

        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val i = Intent(action, Uri.parse("tel:" + Uri.encode(number)))
        return if (launch(ctx, i)) {
            Outcome(true, "Calling $label", "$label · $number")
        } else Outcome(false, "Couldn't open the dialler")
    }

    private fun message(ctx: Context, c: Command.Message): Outcome {
        val digitsOnly = c.who.count { it.isDigit() } >= 7 && c.who.none { it.isLetter() }
        val number: String
        val label: String
        if (digitsOnly) {
            number = c.who.filter { it.isDigit() }; label = number
        } else {
            val best = ContactFinder.best(ctx, c.who)
                ?: return Outcome(false, "I couldn't find ${c.who} in your contacts", null,
                    ContactFinder.search(ctx, c.who).takeIf { it.isNotEmpty() })
            number = best.number.filter { it.isDigit() }; label = best.name
        }
        // Indian numbers are stored ten-digit as often as not; wa.me needs the
        // country code.
        val e164 = if (number.length == 10) "91$number" else number.trimStart('0')
        val url = "https://wa.me/$e164" + (c.body?.let { "?text=" + Uri.encode(it) } ?: "")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return if (launch(ctx, i)) Outcome(true, "Opening WhatsApp for $label", c.body)
        else Outcome(false, "WhatsApp isn't installed")
    }

    // ---------- apps and places ----------

    private val ALIASES = mapOf(
        "insta" to "instagram", "wa" to "whatsapp", "whats app" to "whatsapp",
        "yt" to "youtube", "gmaps" to "maps", "google maps" to "maps",
        "play store" to "play store", "camera" to "camera", "gallery" to "gallery"
    )

    private fun openApp(ctx: Context, c: Command.OpenApp): Outcome {
        val want = ALIASES[c.name.lowercase()] ?: c.name.lowercase()
        val pm = ctx.packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        var bestPkg: String? = null
        var bestScore = 0.0
        var bestLabel = ""
        for (a in apps) {
            val label = runCatching { pm.getApplicationLabel(a).toString() }.getOrNull() ?: continue
            if (pm.getLaunchIntentForPackage(a.packageName) == null) continue
            val l = label.lowercase()
            val s = when {
                l == want -> 1.0
                l.startsWith(want) -> 0.9
                l.contains(want) -> 0.75
                a.packageName.lowercase().contains(want) -> 0.6
                else -> 0.0
            }
            if (s > bestScore) { bestScore = s; bestPkg = a.packageName; bestLabel = label }
        }
        val pkg = bestPkg?.takeIf { bestScore >= 0.6 }
            ?: return Outcome(false, "I couldn't find an app called ${c.name}")
        val i = pm.getLaunchIntentForPackage(pkg)
            ?: return Outcome(false, "I couldn't open ${c.name}")
        return if (launch(ctx, i)) Outcome(true, "Opening $bestLabel") else Outcome(false, "Couldn't open $bestLabel")
    }

    private fun navigate(ctx: Context, place: String): Outcome {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            .setPackage("com.google.android.apps.maps")
        if (launch(ctx, nav)) return Outcome(true, "Getting directions to $place")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
        if (launch(ctx, geo)) return Outcome(true, "Opening the map for $place")
        return search(ctx, "$place directions")
    }

    private fun play(ctx: Context, q: String): Outcome {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(android.app.SearchManager.QUERY, q)
        if (launch(ctx, i)) return Outcome(true, "Playing $q")
        val yt = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        return if (launch(ctx, yt)) Outcome(true, "Searching YouTube for $q")
        else Outcome(false, "Nothing here can play that")
    }

    private fun search(ctx: Context, q: String): Outcome {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)
        if (launch(ctx, web)) return Outcome(true, "Searching for $q")
        val url = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        return if (launch(ctx, url)) Outcome(true, "Searching for $q")
        else Outcome(false, "No browser to search with")
    }

    // ---------- device ----------

    private fun torch(ctx: Context, on: Boolean): Outcome {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Outcome(false, "No camera on this phone")
        return runCatching {
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Outcome(false, "This phone has no torch")
            cm.setTorchMode(id, on)
            Outcome(true, if (on) "Torch on" else "Torch off")
        }.getOrElse { Outcome(false, "Couldn't switch the torch") }
    }

    private fun volume(ctx: Context, c: Command.Volume): Outcome {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        return runCatching {
            when (c.kind) {
                Command.Volume.Kind.UP ->
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                Command.Volume.Kind.DOWN ->
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                Command.Volume.Kind.MUTE ->
                    am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                Command.Volume.Kind.SET -> {
                    val max = am.getStreamMaxVolume(stream)
                    val v = ((c.level ?: 50) / 100.0 * max).toInt().coerceIn(0, max)
                    am.setStreamVolume(stream, v, AudioManager.FLAG_SHOW_UI)
                }
                // Ringer mode needs Do-Not-Disturb access, which is a settings
                // screen, not a runtime prompt.
                Command.Volume.Kind.SILENT, Command.Volume.Kind.LOUD -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        return Outcome(false, "Give Boss Do Not Disturb access first")
                    }
                    am.ringerMode = if (c.kind == Command.Volume.Kind.SILENT)
                        AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
                }
            }
            Outcome(true, c.title)
        }.getOrElse { Outcome(false, "Couldn't change the volume") }
    }

    private fun battery(ctx: Context): Outcome {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val s = String.format(Locale.ENGLISH, "Battery is %d percent%s", pct, if (charging) " and charging" else "")
        return Outcome(true, s)
    }
}
