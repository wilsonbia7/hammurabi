package dev.menzo.hammurabi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Hammurabi's Quick Settings tile: captions on and off from any app, without going to Hammurabi. On, it asks
 * for screen sharing over the app in front (`StartActivity`) and starts what was chosen last.
 */
class CaptionTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update(CaptionService.running)
    }

    override fun onClick() {
        super.onClick()
        Log.i("hammurabi", "tile: clicked, captions ${if (CaptionService.running) "on" else "off"}")
        if (CaptionService.running) {
            startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
            update(false)
            return
        }
        // On the lock screen, Android asks to unlock first: screen sharing is asked for over the app then.
        if (isLocked) unlockAndRun { askToStart() } else askToStart()
    }

    // The Intent form, deprecated and refused from Android 14, is the only one before it; it is called
    // only there.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun askToStart() {
        val start = Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, start, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(start)
        }
    }

    private fun update(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }
}
