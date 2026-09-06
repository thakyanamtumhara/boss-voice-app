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
    }

    companion object {
        const val CH_SERVICE = "boss_service"
        const val CH_REMINDER = "boss_reminder"
        const val CH_ALERT = "boss_alert"

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
        }
    }
}
