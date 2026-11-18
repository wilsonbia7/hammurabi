package dev.menzo.hammurabi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display

/**
 * Pictures of the whole screen, one when asked (the bubble tapped). The screen is mirrored only while one
 * is about to be taken ([wake], as the bubble is pressed), so it costs nothing the rest of the time. Waking
 * comes before what is on the screen changes (the bubble going away), so a picture of the change comes
 * even when nothing else moves. Pictures are at the screen's own size, which follows the screen as it turns.
 */
class ScreenCapture(private val context: Context, private val projection: MediaProjection) {
    private val thread = HandlerThread("hm-screen").apply { start() }
    private val handler = Handler(thread.looper)

    /** On [thread] only, but for [display], released at once by [stop] under [this]'s lock. */
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var size = 0 to 0

    /** The newest picture, held until a newer one comes or it is taken. */
    private var held: Image? = null
    private var awake = false
    private var stopped = false

    private val sleep = Runnable { asleep() }

    /** Sets up the mirrored display, nothing mirrored onto it yet; on Android 14 there is one per sharing. */
    fun start() {
        handler.post { open() }
    }

    /** A picture is about to be taken: the screen is mirrored until it is, or for a few seconds. */
    fun wake() {
        handler.post {
            if (stopped) return@post
            open()
            if (!awake) {
                awake = true
                synchronized(this) { reader?.let { display?.surface = it.surface } }
            }
            handler.removeCallbacks(sleep)
            handler.postDelayed(sleep, AWAKE_MS)
        }
    }

    /**
     * The screen once what changed on it (the bubble gone, drawn to the screen by now) is on it too, to [done]
     * on the main thread; null when no picture came. A change is on the mirror a frame or two after it is
     * drawn: the newest picture [SETTLE_MS] later has it; when none came by then, the first within [WAIT_MS].
     */
    fun grab(done: (Bitmap?) -> Unit) {
        val main = Handler(context.mainLooper)
        wake()
        handler.post {
            val settled = SystemClock.uptimeMillis() + SETTLE_MS
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            val check = object : Runnable {
                override fun run() {
                    val now = SystemClock.uptimeMillis()
                    val image = held
                    if (!stopped && (now < settled || (image == null && now < deadline))) {
                        handler.postDelayed(this, 10)
                        return
                    }
                    if (image == null) AppLog.w("screen: no picture came")
                    val bitmap = image?.let { copy(it) }
                    held?.close()
                    held = null
                    asleep()
                    main.post { done(bitmap) }
                }
            }
            check.run()
        }
    }

    fun stop() {
        // At once, not after a picture being copied: the screen sharing ends now.
        synchronized(this) {
            stopped = true
            display?.release()
            display = null
        }
        handler.post {
            held?.close()
            held = null
            reader?.close()
            reader = null
            thread.quitSafely()
        }
    }

    /** The screen turned: the next picture is taken at its new size. */
    fun resize() {
        handler.post { if (!stopped) open() }
    }

    private fun asleep() {
        handler.removeCallbacks(sleep)
        if (!awake) return
        awake = false
        synchronized(this) { display?.surface = null }
    }

    private fun open() {
        val metrics = DisplayMetrics()
        val screen = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        screen.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        synchronized(this) {
            if (stopped || (display != null && size == (w to h))) return
            val fresh = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            fresh.setOnImageAvailableListener({ r ->
                // Only the newest is kept: the mirror runs at the screen's rate.
                val image = try {
                    r.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                } ?: return@setOnImageAvailableListener
                held?.close()
                held = image
            }, handler)
            val d = display
            if (d == null) {
                display = projection.createVirtualDisplay(
                    "Hammurabi screen", w, h, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, if (awake) fresh.surface else null, null, handler,
                )
            } else {
                // One virtual display per sharing since Android 14: it is resized, not made anew.
                d.resize(w, h, metrics.densityDpi)
                d.surface = if (awake) fresh.surface else null
            }
            held?.close()
            held = null
            reader?.close()
            reader = fresh
            size = w to h
            AppLog.i("screen: mirroring at ${w}x$h")
        }
    }

    /** The picture's RGBA rows, which may be padded past its width, as a bitmap of its size. */
    private fun copy(image: Image): Bitmap? = try {
        val plane = image.planes[0]
        val padded = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer.rewind())
        if (padded == image.width) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { full.recycle() }
    } catch (e: RuntimeException) {
        AppLog.e("screen: picture not copied", e)
        null
    }

    private companion object {
        /** How long the screen stays mirrored after a press without a picture taken (a drag, a hold). */
        const val AWAKE_MS = 4_000L

        /** A frame or two at 60 Hz, and room for a busy moment. */
        const val SETTLE_MS = 120L

        /** Pictures come at the screen's rate when anything moves; the bubble going is a move. */
        const val WAIT_MS = 800L
    }
}
