package com.ketu.boss.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ReminderScheduler {
    private const val TAG = "BossReminder"

    private fun intentFor(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, ReminderReceiver::class.java)
            .setAction("com.ketu.boss.FIRE")
            .setData(android.net.Uri.parse("boss://reminder/$id"))
            .putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intentFor(ctx, r.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Should not happen — the app declares USE_EXACT_ALARM — but an
                // approximate alarm is far better than silently none.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
                Log.w(TAG, "no exact-alarm permission; scheduled inexactly")
                return
            }
            // setAlarmClock is the strongest tier: it survives Doze and app
            // standby, which is exactly what Samsung's battery manager fights.
            val show = PendingIntent.getActivity(
                ctx, r.id + 500_000, Intent(ctx, com.ketu.boss.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(r.atMillis, show), pi)
        } catch (t: Throwable) {
            Log.e(TAG, "schedule failed", t)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi) }
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(intentFor(ctx, id)) }
        ReminderStore.remove(ctx, id)
    }

    /** Alarms do not survive a reboot; the stored list does. */
    fun rearmAll(ctx: Context) {
        ReminderStore.pending(ctx).forEach { schedule(ctx, it) }
    }
}
