package dev.menzo.hammurabi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * An invisible screen over the app in front, which only asks Android for something, then goes: screen
 * sharing to start from the Quick Settings tile, screen sharing again after it ended, or the microphone for
 * subtitles turned on from the bubble. What needs explaining is asked on the main screen instead.
 */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turned while Android asks: the answer comes to this one.
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_MICROPHONE -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MICROPHONE)
            MODE_SHARE -> startActivityForResult(Launch.consent(this), REQ_SHARE)
            else -> fromTile()
        }
    }

    private fun fromTile() {
        val c = Prefs.load(this)
        if (Launch.missingPermissions(this, c, notifications = false).isNotEmpty() || !Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        startActivityForResult(Launch.consent(this), REQ_START)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MICROPHONE) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // Now, while this is in front: Android lets a service in front take the microphone.
                CaptionService.instance?.microphoneGranted()
            } else {
                Prefs.update(this) { it.copy(subtitles = false) }
                Toast.makeText(this, R.string.need_microphone, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            when (requestCode) {
                REQ_START -> Launch.start(this, resultCode, data)
                REQ_SHARE -> Launch.start(this, resultCode, data, CaptionService.ACTION_SHARE)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SHARE = "share"
        const val MODE_MICROPHONE = "microphone"
        private const val REQ_START = 1
        private const val REQ_SHARE = 2
        private const val REQ_MICROPHONE = 3
    }
}
