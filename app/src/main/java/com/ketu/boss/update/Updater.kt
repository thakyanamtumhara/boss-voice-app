package com.ketu.boss.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.ketu.boss.BuildConfig
import com.ketu.boss.Diagnostics
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps Boss up to date by itself.
 *
 * Android will not let a normal app install another app silently, and it
 * should not. What it does allow is an app updating *itself* through
 * PackageInstaller once "install unknown apps" is granted — and from
 * Android 12, without a tap at all when the app already owns its own updates.
 * Where that is refused we fall back to the one-tap system dialog rather than
 * failing quietly.
 */
object Updater {

    private const val TAG = "BossUpdate"
    const val REPO = "thakyanamtumhara/boss-voice-app"

    data class Release(val version: String, val tag: String, val url: String, val size: Long, val notes: String)

    /** "1.0.4" -> 10004, so 1.0.10 sorts above 1.0.9. */
    private fun code(v: String): Long {
        val parts = v.trim().removePrefix("v").split(".").mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        var n = 0L
        for (i in 0 until 3) n = n * 1000 + (parts.getOrNull(i) ?: 0)
        return n
    }

    fun isNewer(candidate: String, current: String = BuildConfig.VERSION_NAME) =
        code(candidate) > code(current)

    /** Asks GitHub what the latest release is. Blocking — call off the main thread. */
    fun latest(): Release? = runCatching {
        val c = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection)
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Boss/${BuildConfig.VERSION_NAME}")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(body)
        val tag = o.optString("tag_name")
        val assets = o.optJSONArray("assets") ?: return@runCatching null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk")) {
                return@runCatching Release(
                    version = tag.removePrefix("v"),
                    tag = tag,
                    url = a.optString("browser_download_url"),
                    size = a.optLong("size"),
                    notes = o.optString("body").take(500)
                )
            }
        }
        null
    }.onFailure { Log.w(TAG, "release check failed", it) }.getOrNull()

    /** Downloads to private storage. Blocking. Returns null on any failure. */
    fun download(ctx: Context, r: Release): File? = runCatching {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "boss-${r.version}.apk")
        val c = (URL(r.url).openConnection() as HttpURLConnection)
        c.connectTimeout = 20_000; c.readTimeout = 120_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Boss/${BuildConfig.VERSION_NAME}")
        c.inputStream.use { ins -> out.outputStream().use { ins.copyTo(it, 128 * 1024) } }
        c.disconnect()
        // A truncated download would be rejected by the installer anyway, but
        // catching it here keeps the failure legible.
        if (r.size > 0 && out.length() != r.size) {
            Log.w(TAG, "size mismatch: got ${out.length()} want ${r.size}")
            out.delete(); return@runCatching null
        }
        out
    }.onFailure { Log.w(TAG, "download failed", it) }.getOrNull()

    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.packageManager.canRequestPackageInstalls()
        else true

    /**
     * Hands the APK to the system installer. If Android decides a tap is
     * needed, [InstallResultReceiver] surfaces that as a notification rather
     * than dropping it.
     */
    fun install(ctx: Context, apk: File, version: String): Boolean = runCatching {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Honoured only when Boss already owns its own updates — i.e. from
            // the second self-update onward. Otherwise Android asks; that is
            // its call to make, not ours.
            runCatching {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            session.openWrite("boss.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 128 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION)
                .putExtra(InstallResultReceiver.EXTRA_VERSION, version)
            val sender = PendingIntent.getBroadcast(
                ctx, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            ).intentSender
            session.commit(sender)
        }
        Log.i(TAG, "install session $sessionId committed for $version")
        true
    }.onFailure {
        Log.e(TAG, "install failed", it)
        Diagnostics.report(ctx, "update_install_failed", it.toString(), force = true)
    }.getOrDefault(false)
}
