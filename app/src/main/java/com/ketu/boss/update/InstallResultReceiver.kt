package com.ketu.boss.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ketu.boss.BossApp
import com.ketu.boss.Diagnostics
import com.ketu.boss.MainActivity
import com.ketu.boss.R

/** What the system said about the install we asked for. */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.ketu.boss.INSTALL_RESULT"
        const val EXTRA_VERSION = "version"
        private const val NOTIF = 77
        private const val TAG = "BossUpdate"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: "new version"
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status msg=$msg")
        BossApp.createChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a tap. Surface it as a notification instead of
                // trying to shove an activity in front of whatever he is doing.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                val pi = PendingIntent.getActivity(
                    ctx, 21, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                nm.notifyIfAllowed(ctx, NOTIF,
                    NotificationCompat.Builder(ctx, BossApp.CH_ALERT)
                        .setSmallIcon(R.drawable.ic_tile)
                        .setContentTitle("Boss $version is ready")
                        .setContentText("Tap to install — it is already downloaded")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .build())
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // The process was replaced, so the microphone service is gone
                // and Android 14 will not let it restart from the background.
                // One tap is genuinely required here; say so plainly.
                val open = PendingIntent.getActivity(
                    ctx, 22,
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                nm.notifyIfAllowed(ctx, NOTIF,
                    NotificationCompat.Builder(ctx, BossApp.CH_REMINDER)
                        .setSmallIcon(R.drawable.ic_tile)
                        .setContentTitle("Boss updated to $version")
                        .setContentText("Tap to start listening again")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setOngoing(true)
                        .setContentIntent(open)
                        .addAction(0, "Resume listening", open)
                        .build())
                Diagnostics.report(ctx, "updated", "now on $version", force = true)
            }

            else -> {
                Diagnostics.report(ctx, "update_failed", "status=$status msg=$msg", force = true)
                nm.notifyIfAllowed(ctx, NOTIF,
                    NotificationCompat.Builder(ctx, BossApp.CH_ALERT)
                        .setSmallIcon(R.drawable.ic_tile)
                        .setContentTitle("Boss could not update itself")
                        .setContentText(msg ?: "status $status")
                        .setAutoCancel(true)
                        .build())
            }
        }
    }
}

private fun NotificationManagerCompat.notifyIfAllowed(ctx: Context, id: Int, n: android.app.Notification) {
    runCatching { notify(id, n) }
}
