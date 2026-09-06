package com.ketu.boss.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ketu.boss.BuildConfig
import com.ketu.boss.Diagnostics
import com.ketu.boss.Prefs.autoUpdate
import com.ketu.boss.Prefs.lastUpdateCheck
import com.ketu.boss.Prefs.updateAttempt
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub for a newer Boss, downloads it and hands it to the installer.
 *
 * Wi-Fi only when it runs on its own — the APK is 50 MB and spending someone's
 * mobile data unasked is not a decision this should make. The manual button in
 * the app works on any connection.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!ctx.autoUpdate) return Result.success()
        return if (runCheck(ctx, manual = false)) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "BossUpdate"
        private const val WORK = "boss-update-check"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(ctx)
                    .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
            }.onFailure { Log.w(TAG, "could not schedule update check", it) }
        }

        fun cancel(ctx: Context) {
            runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
        }

        /**
         * The whole cycle. Returns false only on a transient failure worth
         * retrying. [manual] skips the back-off so the button always acts.
         */
        fun runCheck(ctx: Context, manual: Boolean): Boolean {
            ctx.lastUpdateCheck = System.currentTimeMillis()
            val r = Updater.latest() ?: return false
            if (!Updater.isNewer(r.version)) {
                Log.i(TAG, "already on the newest (${BuildConfig.VERSION_NAME})")
                return true
            }

            // A version that fails to install must not be re-downloaded every
            // six hours forever. Three tries, then wait for a newer one.
            val (lastVersion, tries) = ctx.updateAttempt
            if (!manual && lastVersion == r.version && tries >= 3) {
                Log.w(TAG, "giving up on ${r.version} after $tries tries")
                return true
            }
            ctx.updateAttempt = r.version to (if (lastVersion == r.version) tries + 1 else 1)

            if (!Updater.canInstall(ctx)) {
                Diagnostics.report(ctx, "update_blocked",
                    "install-unknown-apps is off; ${r.version} downloaded but cannot be installed",
                    force = true)
                // Still download it, so the tap is instant once permitted.
            }

            Log.i(TAG, "downloading ${r.version} (${r.size} bytes)")
            val apk = Updater.download(ctx, r) ?: return false
            if (!Updater.canInstall(ctx)) return true
            Updater.install(ctx, apk, r.version)
            return true
        }
    }
}
