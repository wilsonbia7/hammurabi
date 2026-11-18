package dev.menzo.hammurabi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Starts captions from the Quick Settings tile, over the app in front: an invisible screen that only asks
 * Android for screen sharing, then goes. What needs explaining (a permission, the overlay) is asked on the
 * main screen instead.
 */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turned while Android asks: the answer comes to this one.
        if (savedInstanceState != null) return
        val c = Prefs.load(this)
        if (Engine.setup(this) != null ||
            Launch.missingPermissions(this, c, notifications = false).isNotEmpty() ||
            (c.overlay && !Settings.canDrawOverlays(this))
        ) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (c.source.shares) {
            startActivityForResult(Launch.consent(this), REQ_PROJECTION)
        } else {
            Launch.start(this, c, 0, null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            Launch.start(this, Prefs.load(this), resultCode, data)
        }
        finish()
    }

    private companion object {
        const val REQ_PROJECTION = 1
    }
}
