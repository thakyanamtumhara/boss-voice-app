package com.ketu.boss

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class BossApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        Diagnostics.installCrashHandler(this)
    }

    companion object {
        const val CH_SERVICE = "boss_service"
        const val CH_REMINDER = "boss_reminder"
        const val CH_ALERT = "boss_alert"
        const val CH_WAKE = "boss_wake"

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)

            // Silent and low-importance: this one just has to sit in the shade
            // all day holding the microphone service up.
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Boss is listening for the wake word."
                    setShowBadge(false)
                }
            )

            // Reminders must be able to wake the screen, so: max importance,
            // alarm audio stream, bypasses Do Not Disturb.
            nm.createNotificationChannel(
                NotificationChannel(CH_REMINDER, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Your reminders, when they come due."
                    enableVibration(true)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )

            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Boss says", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Confirmations and errors from a spoken command."
                }
            )

            // Must be HIGH: a full-screen intent is only honoured from a
            // high-importance channel, and that is the only sanctioned way to
            // put the listening pop-up on a locked screen. Silent, because the
            // chime has already played by the time this fires.
            nm.createNotificationChannel(
                NotificationChannel(CH_WAKE, "Heard the wake word", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Opens the listening pop-up when the screen is off or locked."
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }
}
