package com.ketu.boss.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    companion object { const val EXTRA_ID = "id" }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        val r = ReminderStore.get(ctx, id) ?: return
        ReminderStore.markDone(ctx, id)

        val alert = Intent(ctx, ReminderAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(ReminderAlertActivity.EXTRA_TEXT, r.text)
            .putExtra(ReminderAlertActivity.EXTRA_AT, r.atMillis)
            .putExtra(ReminderAlertActivity.EXTRA_IS_ALARM, r.isAlarm)
        runCatching { ctx.startActivity(alert) }
            .onFailure { ReminderAlertActivity.postFullScreen(ctx, r) }
        // Always leave the notification too, so nothing is lost if the screen
        // was never allowed to come on.
        ReminderAlertActivity.postFullScreen(ctx, r)
    }
}
