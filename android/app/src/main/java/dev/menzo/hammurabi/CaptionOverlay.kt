package dev.menzo.hammurabi

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject

/** Where our captions are on the screen (left, top, right, bottom), or null while none show: not to be read. */
object OverlayArea {
    @Volatile
    var rect: IntArray? = null
}

/**
 * Captions over every app, at the bottom middle of the screen like a film's subtitles; touches go through to
 * the app below. Until there are captions it shows how things go (a model downloading, an error), so the
 * user is not left guessing in another app. Needs the "display over other apps" permission.
 *
 * [translatedOnly]: only translations, for text read off the screen, whose original is there already.
 */
class CaptionOverlay(
    private val context: Context,
    private val showOriginal: Boolean,
    private val translatedOnly: Boolean,
    private val source: Source,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val captions = Captions()
    private val metrics = context.resources.displayMetrics
    private val bigPx = sp(20f)
    private val smallPx = sp(15f)
    private var shown = false
    private var status: StatusText.Line? = null
    private var statusUntil = 0L

    private val view = TextView(context).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.1f)
        setShadowLayer(4f, 0f, 1f, 0xff000000.toInt())
        val pad = dp(10f)
        setPadding(pad, dp(6f), pad, dp(6f))
        background = GradientDrawable().apply {
            setCornerRadius(dp(8f).toFloat())
            // `CaptionStyle::bg_opacity` 0.75.
            setColor(0xbf000000.toInt())
        }
        maxWidth = (metrics.widthPixels * 0.94f).toInt()
        // A long translation stays within the lower part of the screen.
        maxLines = MAX_LINES
        visibility = View.INVISIBLE
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishArea() }
    }

    private val listener: (JSONObject) -> Unit = { event ->
        when (event.optString("t")) {
            "final", "live", "clear" -> captions.apply(event)
            else -> StatusText.of(context, event, source)?.let { line ->
                status = line
                statusUntil = if (line.ttlMs > 0) SystemClock.uptimeMillis() + line.ttlMs else Long.MAX_VALUE
            }
        }
        render()
    }

    /** Drops captions and lines whose time is up, between events. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    fun show() {
        if (shown) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Above the navigation bar, and video players' and apps' bottom bars.
            y = (metrics.heightPixels * 0.10f).toInt()
            // Since Android 12 touches only pass through another app's window up to this opacity.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = (context.getSystemService(Context.INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
            }
        }
        try {
            wm.addView(view, params)
        } catch (e: RuntimeException) {
            // The permission was taken back meanwhile: captions stay on Hammurabi's screen.
            return
        }
        shown = true
        CaptionBus.add(listener)
        // What happened before this showed: a download under way, say.
        CaptionBus.last.values.toList().forEach { listener(it) }
        handler.post(tick)
    }

    fun hide() {
        if (!shown) return
        shown = false
        CaptionBus.remove(listener)
        handler.removeCallbacks(tick)
        OverlayArea.rect = null
        runCatching { wm.removeView(view) }
    }

    private fun render() {
        var text: CharSequence = captions.text(bigPx, smallPx, showOriginal, translatedOnly)
        val line = status?.takeIf { SystemClock.uptimeMillis() < statusUntil }
        if (text.isEmpty() && line != null) {
            text = SpannableString(line.text).apply {
                setSpan(AbsoluteSizeSpan(smallPx), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(if (line.error) ERROR else STATUS), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        view.text = text
        // An invisible window lets touches through whatever its opacity.
        view.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        publishArea()
    }

    private fun publishArea() {
        if (!shown || view.visibility != View.VISIBLE || view.width == 0) {
            OverlayArea.rect = null
            return
        }
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        OverlayArea.rect = intArrayOf(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics).toInt()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, metrics).toInt()

    private companion object {
        const val MAX_LINES = 8
        val STATUS = Color.parseColor("#e6e6e6")
        val ERROR = Color.parseColor("#ff8a8a")
    }
}
