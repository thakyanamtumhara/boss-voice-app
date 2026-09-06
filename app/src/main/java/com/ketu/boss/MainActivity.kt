package com.ketu.boss

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ketu.boss.Prefs.callNeedsConfirm
import com.ketu.boss.Prefs.clearHistory
import com.ketu.boss.Prefs.history
import com.ketu.boss.Prefs.listenMode
import com.ketu.boss.Prefs.listening
import com.ketu.boss.Prefs.sensitivity
import com.ketu.boss.Prefs.speakReplies
import com.ketu.boss.Prefs.spokenWakeWord
import com.ketu.boss.databinding.ActivityMainBinding
import com.ketu.boss.parse.Fmt
import com.ketu.boss.reminders.ReminderScheduler
import com.ketu.boss.reminders.ReminderStore

/** Control panel: turn listening on, fix the permissions Android needs, see what happened. */
class MainActivity : AppCompatActivity() {

    companion object { const val EXTRA_AUTOSTART = "autostart" }

    private lateinit var b: ActivityMainBinding

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra(WakeService.EXTRA_STATE) ?: return
            val d = i.getStringExtra(WakeService.EXTRA_DETAIL).orEmpty()
            showState(s, d)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        BossApp.createChannels(this)
        Speaker.init(this)

        b.version.text = "v" + BuildConfig.VERSION_NAME
        b.footer.text = "Boss v${BuildConfig.VERSION_NAME} · offline wake word · built for the Galaxy S23"
        b.examples.text = listOf(
            "“set an alarm for 6:30 am”",
            "“wake me at saade 6”",
            "“timer for 10 minutes”",
            "“remind me at 5 to call the CA”",
            "“remind me in 2 hours to check the stock”",
            "“shaam 6 baje yaad dilana godam band karna”",
            "“call Rajesh”  ·  “Rajesh ko call karo”",
            "“whatsapp Rajesh saying I'll be there in 10 minutes”",
            "“open Instagram”  ·  “navigate to Sadar Bazar”",
            "“torch on”  ·  “what's the time”  ·  “battery”"
        ).joinToString("\n")

        b.samsungTip.text =
            "Samsung tip — One UI puts apps to sleep and that stops the wake word.\n" +
            "Settings → Battery → Background usage limits → Never sleeping apps → add Boss.\n" +
            "Also Settings → Apps → Boss → Battery → Unrestricted."

        b.listenSwitch.setOnCheckedChangeListener { _, on ->
            if (!b.listenSwitch.isPressed) return@setOnCheckedChangeListener
            if (on) turnOn() else turnOff()
        }
        b.speakNow.setOnClickListener {
            startActivity(Intent(this, CommandActivity::class.java))
        }
        b.teach.setOnClickListener {
            startActivity(Intent(this, TeachWakeActivity::class.java))
        }
        b.modeAlways.setOnClickListener { setMode(Prefs.MODE_ALWAYS) }
        b.modeScreen.setOnClickListener { setMode(Prefs.MODE_SCREEN_ON) }
        b.modeCharging.setOnClickListener { setMode(Prefs.MODE_CHARGING) }
        b.speakSwitch.setOnCheckedChangeListener { _, v ->
            if (b.speakSwitch.isPressed) speakReplies = v
        }
        b.callSwitch.setOnCheckedChangeListener { _, v ->
            if (b.callSwitch.isPressed) callNeedsConfirm = v
        }
        b.sensBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { sensitivity = p; showSensitivity() }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                if (listening) WakeService.start(this@MainActivity)
            }
        })

        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) turnOn()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(WakeService.BROADCAST_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
        showState(WakeService.state, WakeService.detail)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(stateReceiver) }
    }

    // ---------- actions ----------

    private fun turnOn() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { askPermissions.launch(missing.toTypedArray()); return }
        listening = true
        WakeService.start(this)
        refresh()
    }

    private fun turnOff() {
        listening = false
        WakeService.stop(this)
        refresh()
    }

    private fun setMode(m: String) {
        listenMode = m
        if (listening) WakeService.start(this)
        refresh()
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------- rendering ----------

    private fun refresh() {
        b.listenSwitch.isChecked = listening
        b.wakeWordLine.text = "“" + spokenWakeWord + "”"
        b.speakSwitch.isChecked = speakReplies
        b.callSwitch.isChecked = callNeedsConfirm
        b.sensBar.progress = sensitivity
        showSensitivity()
        when (listenMode) {
            Prefs.MODE_SCREEN_ON -> b.modeScreen.isChecked = true
            Prefs.MODE_CHARGING -> b.modeCharging.isChecked = true
            else -> b.modeAlways.isChecked = true
        }
        buildChecklist()
        buildReminders()
        buildHistory()
    }

    private fun showSensitivity() {
        b.sensLabel.text = "Wake word sensitivity — " + when (sensitivity) {
            0 -> "loose (wakes easily, more false wakes)"
            2 -> "strict (fewer false wakes, say it clearly)"
            else -> "normal"
        }
    }

    private fun showState(s: String, d: String) {
        b.status.text = when (s) {
            "listening" -> d.ifBlank { "Listening" }
            "loading" -> d.ifBlank { "Getting ready…" }
            "error" -> "Not listening — $d"
            "paused" -> d.ifBlank { "Paused" }
            "idle" -> d.ifBlank { "Waiting" }
            "heard" -> "Heard you"
            else -> if (listening) "Starting…" else "Off"
        }
        // Amber = live, grey = not. No red/green anywhere.
        b.statusDot.setBackgroundResource(
            if (s == "listening" || s == "heard") R.drawable.dot_amber else R.drawable.dot_grey
        )
    }

    // ---------- checklist ----------

    private data class Check(
        val title: String, val why: String, val ok: Boolean, val fix: (() -> Unit)?
    )

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun buildChecklist() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val checks = buildList {
            add(Check("Microphone", "To hear the wake word at all", granted(Manifest.permission.RECORD_AUDIO)) {
                askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Check("Notifications", "The listening service needs one", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                })
            }
            add(Check("Notifications enabled", "Reminders ring through this",
                NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                openAppSettings()
            })
            add(Check("Contacts", "So “call Rajesh” finds Rajesh", granted(Manifest.permission.READ_CONTACTS)) {
                askPermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            })
            add(Check("Phone", "So it can dial without another tap", granted(Manifest.permission.CALL_PHONE)) {
                askPermissions.launch(arrayOf(Manifest.permission.CALL_PHONE))
            })
            add(Check("Battery unrestricted", "Samsung kills the mic service otherwise",
                pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }.onFailure { openAppSettings() }
            })
            add(Check("Appear on top", "Lets the pop-up open while the phone is locked",
                Settings.canDrawOverlays(this@MainActivity)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure { openAppSettings() }
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Check("Exact alarms", "Reminders fire to the minute", am.canScheduleExactAlarms()) {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                    }.onFailure { openAppSettings() }
                })
            }
        }

        b.checklist.removeAllViews()
        checks.forEach { c -> b.checklist.addView(checkRow(c)) }
    }

    private fun checkRow(c: Check): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(10, 12, 10, 12)
        }
        // State is carried by the word and the icon, never by colour alone.
        val mark = TextView(this).apply {
            text = if (c.ok) "✓" else "!"
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, if (c.ok) R.color.amber else R.color.text_lo))
            width = 60
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = c.title
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_hi))
        })
        col.addView(TextView(this).apply {
            text = if (c.ok) "Done" else c.why
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_lo))
        })
        row.addView(mark); row.addView(col)
        if (!c.ok && c.fix != null) {
            row.addView(Button(this).apply {
                text = "Fix"
                isAllCaps = false
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(ContextCompat.getColor(context, R.color.amber))
                setOnClickListener { c.fix.invoke() }
            })
        }
        return row
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    // ---------- lists ----------

    private fun buildReminders() {
        b.reminderList.removeAllViews()
        val items = ReminderStore.pending(this)
        if (items.isEmpty()) {
            b.reminderList.addView(muted("Nothing pending."))
            return
        }
        items.forEach { r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(4, 10, 4, 10)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = r.text; textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            })
            col.addView(TextView(this).apply {
                text = Fmt.clockAndDay(r.atMillis); textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.amber))
            })
            row.addView(col)
            row.addView(Button(this).apply {
                text = "Cancel"; isAllCaps = false
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(ContextCompat.getColor(context, R.color.text_lo))
                setOnClickListener { ReminderScheduler.cancel(this@MainActivity, r.id); buildReminders() }
            })
            b.reminderList.addView(row)
        }
    }

    private fun buildHistory() {
        b.historyList.removeAllViews()
        val h = history()
        if (h.isEmpty()) { b.historyList.addView(muted("Nothing yet.")); return }
        h.take(12).forEach { line ->
            val parts = line.split("|", limit = 3)
            val ts = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val ok = parts.getOrNull(1) == "OK"
            val what = parts.getOrNull(2).orEmpty()
            b.historyList.addView(TextView(this).apply {
                text = (if (ok) "· " else "× ") + what + "   " + Fmt.clock(ts)
                textSize = 13f
                setPadding(4, 7, 4, 7)
                setTextColor(ContextCompat.getColor(context, if (ok) R.color.text_hi else R.color.text_lo))
            })
        }
        b.historyList.addView(Button(this).apply {
            text = "Clear"; isAllCaps = false
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(ContextCompat.getColor(context, R.color.text_lo))
            setOnClickListener { clearHistory(); buildHistory() }
        })
    }

    private fun muted(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setPadding(4, 8, 4, 8)
        setTextColor(ContextCompat.getColor(context, R.color.text_lo))
    }
}
