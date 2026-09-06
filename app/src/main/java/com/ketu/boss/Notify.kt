package com.ketu.boss

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notify {
    private const val ID_RESUME = 55
    private const val ID_SAID = 56

    fun tapToResume(ctx: Context) {
        BossApp.createChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 9,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, BossApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Boss is not listening")
            .setContentText("Android blocked the mic after restart. Tap to resume.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_RESUME, n) }
    }

    /** A short "here is what I did" note, for when the pop-up has gone. */
    fun said(ctx: Context, title: String, body: String? = null) {
        BossApp.createChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, BossApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .apply { body?.let { setContentText(it); setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_SAID, n) }
    }
}
