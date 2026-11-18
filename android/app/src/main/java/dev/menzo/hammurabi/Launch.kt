package dev.menzo.hammurabi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** Starting Hammurabi, from the main screen or from the Quick Settings tile (`StartActivity`). */
object Launch {
    /** The permissions [c] needs that are not granted. [notifications]: ask for the notification too. */
    fun missingPermissions(context: Context, c: Choices, notifications: Boolean): List<String> {
        val missing = mutableListOf<String>()
        // Subtitles listen: the phone's sound needs it too.
        if (c.subtitles &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The notification is where Hammurabi is stopped from another app; it runs without it too.
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        return missing
    }

    /**
     * Android's screen-sharing consent. The whole screen rather than one app: the user goes from one app to
     * another and translates each.
     */
    fun consent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /** Starts Hammurabi with the consent's result ([action]: start, or sharing again). */
    fun start(context: Context, projectionCode: Int, projectionData: Intent, action: String = CaptionService.ACTION_START) {
        context.startForegroundService(
            Intent(context, CaptionService::class.java)
                .setAction(action)
                .putExtra(CaptionService.EXTRA_PROJECTION_CODE, projectionCode)
                .putExtra(CaptionService.EXTRA_PROJECTION_DATA, projectionData),
        )
    }
}
