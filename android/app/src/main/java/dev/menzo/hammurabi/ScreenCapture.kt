package dev.menzo.hammurabi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
import java.nio.ByteBuffer

/**
 * Pictures of the whole screen, one when asked (the bubble tapped). The screen is mirrored only while one
 * is about to be taken ([wake], as the bubble is pressed), so it costs nothing the rest of the time. Waking
 * comes before what is on the screen changes (the bubble going away), so a picture of the change comes
 * even when nothing else moves. Pictures are at the screen's own size, which follows the screen as it turns.
 */
class ScreenCapture(private val context: Context, private val projection: MediaProjection) {
    /** Takes pictures of part of the screen while live translation runs ([live]); on the capture thread. */
    interface LiveSink {
        /** Not busy with the last picture. */
        fun ready(): Boolean

        /** The part of a screen of this size to take. */
        fun area(width: Int, height: Int): Rect

        /**
         * That part, as it is now; null when the screen has not changed since the last ([AGAIN_MS] later: a
         * screen standing still gives no new pictures). [black]: the whole screen is black, as an app that
         * forbids capture shows.
         */
        fun frame(bitmap: Bitmap?, area: Rect, black: Boolean)
    }

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

    /** Live translation's taker and how often it takes, while it runs. */
    private var sink: LiveSink? = null
    private var every = 300L
    private var lastTaken = 0L
    private var lastGiven = 0L
    private val liveTick = object : Runnable {
        override fun run() {
            val s = sink ?: return
            val image = held
            val now = SystemClock.uptimeMillis()
            if (image != null && s.ready()) {
                val fresh = image.timestamp != lastTaken
                // A screen standing still gives no new pictures; it is told again now and then.
                if (fresh || now - lastGiven >= AGAIN_MS) {
                    lastTaken = image.timestamp
                    lastGiven = now
                    val area = s.area(image.width, image.height)
                    if (!area.isEmpty) {
                        if (fresh) {
                            rows(image, area)?.let { s.frame(it, area, black(image)) }
                        } else {
                            s.frame(null, area, black(image))
                        }
                    }
                }
            }
            handler.postDelayed(this, every)
        }
    }

    /**
     * Live translation: the screen mirrored all along, and a new picture of [LiveSink.area] to [sink] every
     * [everyMs] at most. Null stops it.
     */
    fun live(everyMs: Long, sink: LiveSink?) {
        handler.post {
            if (stopped) return@post
            handler.removeCallbacks(liveTick)
            this.sink = sink
            every = everyMs
            lastTaken = 0L
            if (sink != null) {
                open()
                handler.removeCallbacks(this.sleep)
                if (!awake) {
                    awake = true
                    synchronized(this) { reader?.let { display?.surface = it.surface } }
                }
                handler.post(liveTick)
            } else {
                asleep()
            }
        }
    }

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
            sink = null
            handler.removeCallbacks(liveTick)
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
        // Live translation keeps it mirrored.
        if (!awake || sink != null) return
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

    private fun copy(image: Image): Bitmap? = rows(image, Rect(0, 0, image.width, image.height))

    /**
     * [area] of the picture as a bitmap. Its RGBA rows may be padded past its width, and the last row's
     * padding may be missing from the buffer (some phones): copied through a full-size buffer then.
     */
    private fun rows(image: Image, area: Rect): Bitmap? = try {
        val plane = image.planes[0]
        val stride = plane.rowStride
        val padded = stride / plane.pixelStride
        val top = area.top.coerceIn(0, image.height - 1)
        val count = (area.bottom.coerceAtMost(image.height) - top).coerceAtLeast(1)
        val src = plane.buffer.duplicate()
        val from = top * stride
        val need = count * stride
        val bytes = if (src.capacity() - from >= need) {
            src.position(from)
            src.limit(from + need)
            src.slice()
        } else {
            val whole = ByteBuffer.allocate(need)
            src.position(from)
            whole.put(src)
            whole.rewind()
            whole
        }
        val strip = Bitmap.createBitmap(padded, count, Bitmap.Config.ARGB_8888)
        strip.copyPixelsFromBuffer(bytes)
        val left = area.left.coerceIn(0, image.width - 1)
        val width = (area.right.coerceAtMost(image.width) - left).coerceAtLeast(1)
        if (left == 0 && width == padded) strip else Bitmap.createBitmap(strip, left, 0, width, count).also { strip.recycle() }
    } catch (e: RuntimeException) {
        AppLog.e("screen: picture not copied", e)
        null
    }

    /**
     * Whether the screen below the status bar and above the navigation bar, away from its edges (the
     * bubble), is pure black but for a little (a toast over it): what an app that forbids capture
     * (Netflix, a bank) shows. A dark scene is not pure black all over.
     */
    private fun black(image: Image): Boolean {
        val plane = image.planes[0]
        val buf = plane.buffer
        val stride = plane.rowStride
        var lit = 0
        for (j in 0 until ROWS) {
            val y = (image.height * (0.12f + 0.76f * j / (ROWS - 1))).toInt()
            // Not the screen's edges, where the bubble sits.
            for (i in 0 until COLUMNS) {
                val x = (image.width * (0.15f + 0.7f * i / (COLUMNS - 1))).toInt()
                val at = y * stride + x * 4
                if (at + 2 >= buf.capacity()) continue
                val r = buf.get(at).toInt() and 0xff
                val g = buf.get(at + 1).toInt() and 0xff
                val b = buf.get(at + 2).toInt() and 0xff
                if (maxOf(r, g, b) > 3) lit++
            }
        }
        return lit <= ROWS * COLUMNS * LIT
    }

    /** Whether [bitmap] (a picture taken by [grab]) is pure black but for the status and navigation bars. */
    fun isBlack(bitmap: Bitmap): Boolean {
        var lit = 0
        for (j in 0 until ROWS) {
            val y = (bitmap.height * (0.12f + 0.76f * j / (ROWS - 1))).toInt().coerceIn(0, bitmap.height - 1)
            for (i in 0 until COLUMNS) {
                val c = bitmap.getPixel((bitmap.width * (0.15f + 0.7f * i / (COLUMNS - 1))).toInt().coerceIn(0, bitmap.width - 1), y)
                if (maxOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff) > 3) lit++
            }
        }
        return lit <= ROWS * COLUMNS * LIT
    }

    companion object {
        /** How long the screen stays mirrored after a press without a picture taken (a drag, a hold). */
        const val AWAKE_MS = 4_000L

        /** A frame or two at 60 Hz, and room for a busy moment. */
        const val SETTLE_MS = 120L

        /** How often live translation is told of a screen standing still. */
        const val AGAIN_MS = 900L

        /**
         * A black screen is looked for at this many points; a few lit ones (a toast, a notice of
         * Hammurabi's own over it) still make it black.
         */
        private const val ROWS = 20
        private const val COLUMNS = 16
        private const val LIT = 0.1

        /** Pictures come at the screen's rate when anything moves; the bubble going is a move. */
        const val WAIT_MS = 800L
    }
}
