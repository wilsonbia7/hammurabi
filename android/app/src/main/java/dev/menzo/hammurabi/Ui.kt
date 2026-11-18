package dev.menzo.hammurabi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** What Hammurabi's windows over other apps share: one dark look, readable over any app. */
object Ui {
    const val SURFACE = 0xf21e2025.toInt()
    const val BUTTON = 0xff2e323a.toInt()
    const val TEXT = 0xfff1f3f5.toInt()
    const val TEXT_DIM = 0xffa9afb8.toInt()
    const val ACCENT = 0xff7aa7ff.toInt()
    const val DANGER = 0xffff8a80.toInt()
    private const val RIPPLE = 0x33ffffff

    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun sp(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    /** A background that shows a touch. */
    fun touchable(background: Drawable, radius: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(RIPPLE), background, rounded(-1, radius))

    fun text(context: Context, value: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** A rounded button of text. */
    fun button(context: Context, label: CharSequence, color: Int = TEXT, onClick: () -> Unit) =
        text(context, label, 14f, color, bold = true).apply {
            gravity = Gravity.CENTER
            val r = dp(context, 18f).toFloat()
            background = touchable(rounded(BUTTON, r), r)
            setPadding(dp(context, 14f), dp(context, 9f), dp(context, 14f), dp(context, 9f))
            minHeight = dp(context, 40f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A window over other apps. [touchable]: it takes touches; else they go to the app below. */
    fun overlayParams(
        context: Context,
        width: Int,
        height: Int,
        touchable: Boolean,
        focusable: Boolean = false,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Since Android 12 touches only pass through another app's window up to this opacity.
            if (!touchable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = (context.getSystemService(Context.INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
            }
        }
    }

    /** Puts [text] on the clipboard; below Android 13, which says so itself, a toast tells. */
    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun gone(v: View, gone: Boolean) {
        v.visibility = if (gone) View.GONE else View.VISIBLE
    }
}
