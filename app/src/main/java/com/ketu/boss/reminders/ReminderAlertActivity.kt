package com.ketu.boss.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ketu.boss.BossApp
import com.ketu.boss.R
import com.ketu.boss.Speaker
import com.ketu.boss.databinding.ActivityAlertBinding
import com.ketu.boss.parse.Fmt

/** A reminder coming due: screen on, alarm-loud, and spoken out. */
class ReminderAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_AT = "at"
        const val EXTRA_IS_ALARM = "is_alarm"
        private const val NOTIF_BASE = 6000

        fun postFullScreen(ctx: Context, r: Reminder) {
            BossApp.createChannels(ctx)
            val open = Intent(ctx, ReminderAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_TEXT, r.text).putExtra(EXTRA_AT, r.atMillis)
                .putExtra(EXTRA_IS_ALARM, r.isAlarm)
            val pi = PendingIntent.getActivity(
                ctx, r.id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, BossApp.CH_REMINDER)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(if (r.isAlarm) "Alarm" else "Reminder")
                .setContentText(r.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_BASE + r.id % 1000, n) }
        }
    }

    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val b = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(b.root)

        val isAlarm = intent?.getBooleanExtra(EXTRA_IS_ALARM, false) ?: false
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { if (isAlarm) "Alarm" else "Reminder" }
        val at = intent?.getLongExtra(EXTRA_AT, System.currentTimeMillis()) ?: System.currentTimeMillis()
        b.kindLine.text = if (isAlarm) "ALARM" else "REMINDER"
        b.text.text = text
        b.whenLine.text = Fmt.clockAndDay(at)

        startAlarm()
        Speaker.speak(this, text)

        b.dismiss.setOnClickListener { stopAlarm(); finish() }
        b.snooze.setOnClickListener {
            stopAlarm()
            val r = ReminderStore.add(this, System.currentTimeMillis() + 10 * 60_000L, text)
            ReminderScheduler.schedule(this, r)
            finish()
        }
        // Never ring forever.
        b.root.postDelayed({ stopAlarm() }, 60_000)
    }

    private fun startAlarm() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setDataSource(this@ReminderAlertActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare(); start()
            }
        }
        runCatching {
            vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vib?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 700, 500, 700), 0)
            )
        }
    }

    private fun stopAlarm() {
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { vib?.cancel() }; vib = null
        Speaker.stop()
    }

    override fun onDestroy() { stopAlarm(); super.onDestroy() }
}
