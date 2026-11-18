package dev.menzo.hammurabi

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display

/**
 * Pictures of the screen to Translate the screen, a few a second, handed to the engine
 * (`Native.screenFrame`), which reads those that changed, with where our own captions are in them. The
 * picture follows the screen when it turns, as a video going full screen does.
 */
class ScreenCapture(private val context: Context, private val projection: MediaProjection) {
    private val thread = HandlerThread("hm-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null

    /** Made and resized on [thread], let go at once by [stop]: under [this]'s lock. */
    private var display: VirtualDisplay? = null
    private var size = 0 to 0

    /** The picture's pixels per pixel of the screen. */
    @Volatile
    private var scale = 1f

    /** The shared app's size when the user shares one app (Android 14), else the screen's. */
    private var content: Pair<Int, Int>? = null

    @Volatile
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t = SystemClock.uptimeMillis()
            try {
                reader?.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val m = mask()
                    Native.screenFrame(plane.buffer, image.width, image.height, plane.rowStride, m[0], m[1], m[2], m[3])
                }
            } catch (e: IllegalStateException) {
                // An image went with a reader replaced meanwhile; the next is read.
            }
            handler.postDelayed(this, maxOf(10L, EVERY_MS - (SystemClock.uptimeMillis() - t)))
        }
    }

    fun start() {
        running = true
        handler.post {
            open()
            handler.post(tick)
        }
    }

    fun stop() {
        running = false
        // At once, not after a picture being read: no more pictures, the screen sharing ends now.
        synchronized(this) {
            display?.release()
            display = null
        }
        // The reader after: the picture being read is in its memory.
        handler.post {
            reader?.close()
            reader = null
            thread.quitSafely()
        }
    }

    /** The screen turned, or the shared app took another size ([width] by [height], Android 14). */
    fun resize(width: Int = 0, height: Int = 0) {
        handler.post {
            if (width > 0 && height > 0) content = width to height
            if (running) open()
        }
    }

    /** Where our captions are in the picture, a little wider for their shadow; all 0 while none show. */
    private fun mask(): IntArray {
        val r = OverlayArea.rect ?: return IntArray(4)
        val s = scale
        return intArrayOf(
            ((r[0] - MASK_MARGIN) * s).toInt(),
            ((r[1] - MASK_MARGIN) * s).toInt(),
            ((r[2] + MASK_MARGIN) * s).toInt() + 1,
            ((r[3] + MASK_MARGIN) * s).toInt() + 1,
        )
    }

    private fun open() {
        val metrics = DisplayMetrics()
        val screen = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        screen.getRealMetrics(metrics)
        val (cw, ch) = content ?: (metrics.widthPixels to metrics.heightPixels)
        // Phones' text reads well at 1920 on the long side; a bigger picture would only be more to copy.
        val scale = minOf(1f, MAX_SIDE / maxOf(cw, ch).toFloat())
        this.scale = scale
        val w = maxOf(1, (cw * scale).toInt())
        val h = maxOf(1, (ch * scale).toInt())
        synchronized(this) {
            if (!running || (display != null && size == (w to h))) return
            val fresh = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val d = display
            if (d == null) {
                display = projection.createVirtualDisplay(
                    "Hammurabi screen", w, h, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, fresh.surface, null, handler,
                )
            } else {
                // One virtual display per projection since Android 14: it is resized, not made anew.
                d.resize(w, h, metrics.densityDpi)
                d.surface = fresh.surface
            }
            reader?.close()
            reader = fresh
            size = w to h
        }
    }

    private companion object {
        /** The engine reads a picture that keeps changing twice a second at most; this sees a change soon. */
        const val EVERY_MS = 150L
        const val MAX_SIDE = 1920f
        const val MASK_MARGIN = 6
    }
}
