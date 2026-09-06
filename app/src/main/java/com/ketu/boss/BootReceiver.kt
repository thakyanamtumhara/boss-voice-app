package com.ketu.boss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ketu.boss.Prefs.listening
import com.ketu.boss.reminders.ReminderScheduler

/**
 * After a reboot or an app update: put every pending reminder back on the
 * alarm queue, then try to resume listening.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ReminderScheduler.rearmAll(ctx)
        if (!ctx.listening) return
        // Android 14 refuses a microphone service started from the background,
        // which a boot is. Bounce through an invisible activity so the app is
        // briefly foreground; the notification stays as the fallback if even
        // that launch is refused.
        Notify.resumeVia(ctx, "BossBoot")
    }
}
