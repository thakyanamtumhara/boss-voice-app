package com.ketu.boss

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notify {
    private const val ID_RESUME = 55
    private const val ID_SAID = 56

    /**
     * Gets listening going again after a reboot or a self-update without
     * asking for a tap: an invisible activity makes the app foreground for an
     * instant, which is the state Android 14 requires before a microphone
     * service may start. Falls back to the notification if the launch is
     * refused — which it will be unless "appear on top" is granted.
     */
    fun resumeVia(ctx: Context, tag: String) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(ctx, ResumeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            true
        }.getOrElse { android.util.Log.w(tag, "resume activity refused", it); false }
        // The activity may also be silently dropped, so always leave the
        // notification; it clears itself once listening is really back.
        if (!ok) tapToResume(ctx)
        else android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!WakeService.alive) tapToResume(ctx)
        }, 6000)
    }

    /** Drops the "not listening" notice once listening is genuinely back. */
    fun clearResume(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID_RESUME) }
    }

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
            .setContentText("Android needs one tap after a restart or update. Tap to resume.")
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
