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

/**
 * Automatic subtitles over every app, at the bottom middle of the screen like a film's; touches go through
 * to the app below. Until there are subtitles it shows how things go (a model downloading, an error), so
 * the user is not left guessing in another app.
 */
class CaptionOverlay(private val context: Context, private var choices: Choices) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val captions = Captions()
    private val metrics = context.resources.displayMetrics
    private var shown = false
    private var status: StatusText.Line? = null
    private var statusUntil = 0L

    private val view = TextView(context).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.1f)
        setShadowLayer(4f, 0f, 1f, 0xff000000.toInt())
        val pad = dp(12f)
        setPadding(pad, dp(6f), pad, dp(7f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            // `CaptionStyle::bg_opacity` 0.75.
            setColor(0xbf000000.toInt())
        }
        maxWidth = (metrics.widthPixels * 0.94f).toInt()
        // A long translation stays within the lower part of the screen.
        maxLines = MAX_LINES
        visibility = View.INVISIBLE
    }

    private val listener: (JSONObject) -> Unit = { event ->
        when (event.optString("t")) {
            "final", "live", "clear" -> captions.apply(event)
            else -> StatusText.of(context, event)?.let { line ->
                status = line
                statusUntil = if (line.ttlMs > 0) SystemClock.uptimeMillis() + line.ttlMs else Long.MAX_VALUE
            }
        }
        render()
    }

    /** Drops subtitles and lines whose time is up, between events. */
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
            AppLog.e("subtitles: overlay not shown", e)
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
        runCatching { wm.removeView(view) }
    }

    /** Where the subtitles are on the screen (left, top, right, bottom), or null while none show. */
    val area: IntArray?
        get() {
            if (!shown || view.visibility != View.VISIBLE || view.width == 0) return null
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            return intArrayOf(at[0], at[1], at[0] + view.width, at[1] + view.height)
        }

    /** New settings: the size, the original shown or not. */
    fun update(c: Choices) {
        choices = c
        render()
    }

    private fun render() {
        val big = sp(BIG[choices.textSize.coerceIn(0, 2)])
        val small = sp(SMALL[choices.textSize.coerceIn(0, 2)])
        var text: CharSequence = captions.text(big, small, choices.showOriginal)
        val line = status?.takeIf { SystemClock.uptimeMillis() < statusUntil }
        if (text.isEmpty() && line != null) {
            text = SpannableString(line.text).apply {
                setSpan(AbsoluteSizeSpan(small), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(if (line.error) ERROR else STATUS), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        view.text = text
        // An invisible window lets touches through whatever its opacity.
        view.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics).toInt()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, metrics).toInt()

    private companion object {
        const val MAX_LINES = 8
        val BIG = floatArrayOf(16f, 20f, 25f)
        val SMALL = floatArrayOf(13f, 15f, 18f)
        val STATUS = Color.parseColor("#e6e6e6")
        val ERROR = Color.parseColor("#ff8a8a")
    }
}
