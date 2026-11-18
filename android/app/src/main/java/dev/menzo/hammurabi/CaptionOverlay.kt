package dev.menzo.hammurabi

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject

/**
 * Captions over every app, near the bottom of the screen like the desktop overlay, or at the [top]
 * (out of the band Subtitles on screen reads). Touches go through to the app below. Needs the
 * "display over other apps" permission.
 */
class CaptionOverlay(
    private val context: Context,
    private val showOriginal: Boolean,
    private val top: Boolean = false,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val captions = Captions()
    private val metrics = context.resources.displayMetrics
    private val bigPx = sp(20f)
    private val smallPx = sp(15f)
    private var shown = false

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
        visibility = View.INVISIBLE
    }

    private val listener: (JSONObject) -> Unit = { event ->
        captions.apply(event)
        render()
    }

    /** Drops captions whose time is up, between events. */
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
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            // Clear of the status bar at the top; above video players' controls and the navigation
            // bar at the bottom.
            y = (metrics.heightPixels * if (top) 0.06f else 0.10f).toInt()
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
        handler.post(tick)
    }

    fun hide() {
        if (!shown) return
        shown = false
        CaptionBus.remove(listener)
        handler.removeCallbacks(tick)
        runCatching { wm.removeView(view) }
    }

    private fun render() {
        val text = captions.text(bigPx, smallPx, showOriginal)
        view.text = text
        // An invisible window lets touches through whatever its opacity.
        view.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics).toInt()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, metrics).toInt()
}
