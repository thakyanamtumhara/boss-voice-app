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
        try {
            WakeService.start(ctx)
        } catch (t: Throwable) {
            // Android 14 can refuse to start a microphone service from the
            // background. Leave a tap-to-resume notification instead of
            // pretending listening is on.
            Log.w("BossBoot", "service start refused after boot", t)
            Notify.tapToResume(ctx)
        }
    }
}
