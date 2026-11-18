package dev.menzo.hammurabi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings

/** Starting captions, from the main screen or from the Quick Settings tile (`StartActivity`). */
object Launch {
    /** The permissions [c] needs that are not granted. [notifications]: ask for the notification too. */
    fun missingPermissions(context: Context, c: Choices, notifications: Boolean): List<String> {
        val missing = mutableListOf<String>()
        // Other apps' sound needs it too; reading the screen does not.
        if (c.source.listens &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The notification is where captions are stopped from another app; captions run without it too.
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        return missing
    }

    /**
     * Android's screen-sharing consent. The whole screen rather than one app: the text of every app is read,
     * or its sound heard, as the user goes from one to another.
     */
    fun consent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /** Starts captions with [c], and the consent's result when [c] shares the screen. */
    fun start(context: Context, c: Choices, projectionCode: Int, projectionData: Intent?) {
        val intent = Intent(context, CaptionService::class.java)
            .setAction(CaptionService.ACTION_START)
            .putExtra(CaptionService.EXTRA_SOURCE, c.source.name)
            .putExtra(CaptionService.EXTRA_SETTINGS, c.settingsJson())
            .putExtra(CaptionService.EXTRA_OVERLAY, c.overlay && Settings.canDrawOverlays(context))
            .putExtra(CaptionService.EXTRA_SHOW_ORIGINAL, c.showOriginal)
        if (projectionData != null) {
            intent.putExtra(CaptionService.EXTRA_PROJECTION_CODE, projectionCode)
                .putExtra(CaptionService.EXTRA_PROJECTION_DATA, projectionData)
        }
        context.startForegroundService(intent)
    }
}
