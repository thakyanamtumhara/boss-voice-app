package com.ketu.boss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ketu.boss.Prefs.listening

/** The buttons on the ongoing notification. */
class ServiceControlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            WakeService.ACTION_STOP -> {
                ctx.listening = false
                WakeService.stop(ctx)
            }
            else -> {
                runCatching {
                    ctx.startService(Intent(ctx, WakeService::class.java).setAction(action))
                }
            }
        }
    }
}
