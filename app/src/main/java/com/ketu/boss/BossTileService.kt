package com.ketu.boss

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.ketu.boss.Prefs.listening

/** Quick-Settings tile: talk to Boss without saying the wake word. */
@RequiresApi(Build.VERSION_CODES.N)
class BossTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, CommandActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
