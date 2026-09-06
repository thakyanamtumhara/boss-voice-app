package com.ketu.boss

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.ketu.boss.Prefs.listening

/**
 * An invisible activity whose only job is to be in the foreground for an
 * instant.
 *
 * From Android 14 a microphone foreground service may only be START-ed while
 * the app counts as foreground, which a reboot or a self-update does not. That
 * is why listening died after both and needed a tap. Starting an activity puts
 * the app in the foreground, and an app holding "appear on top" is allowed to
 * start one from the background — so this stands in for the tap.
 *
 * Shows nothing and finishes immediately. If the launch is refused, the
 * caller's tap-to-resume notification is still there as the fallback.
 */
class ResumeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (listening) {
            Log.i("BossResume", "resuming the microphone service from the foreground")
            runCatching { WakeService.start(this) }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
