package dev.menzo.hammurabi

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.abs

/** The screen's full size, bars included: where windows over other apps are placed. */
fun screenSize(context: Context): Pair<Int, Int> {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        .getRealMetrics(metrics)
    return metrics.widthPixels to metrics.heightPixels
}

/**
 * The small round button over every app, at the screen's edge: tap it to translate the screen, hold it for
 * the menu, drag it anywhere (it goes back to the nearest edge, and stays there next time).
 */
class Bubble(private val context: Context, private val events: Events) {
    interface Events {
        /** A finger came down on it: a tap may follow. */
        fun onPress()
        fun onTap()
        fun onHold()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val size = Ui.dp(context, 46f)
    private val margin = Ui.dp(context, 4f)
    private val view = BubbleView()
    private val params = Ui.overlayParams(context, size, size, touchable = true).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    private var shown = false
    private var hint: TextView? = null

    /** Spins while the screen is being translated. */
    var busy = false
        set(value) {
            field = value
            view.spin(value)
        }

    /** Marked when screen sharing ended: a tap asks for it again. */
    var needsSharing = false
        set(value) {
            field = value
            view.invalidate()
        }

    /** Ringed while live translation runs. */
    var live = false
        set(value) {
            field = value
            view.invalidate()
        }

    private val dim = Runnable { view.animate().alpha(IDLE_ALPHA).setDuration(300).start() }

    fun show() {
        if (shown) return
        place()
        try {
            wm.addView(view, params)
        } catch (e: RuntimeException) {
            AppLog.e("bubble: not shown", e)
            return
        }
        shown = true
        awake()
        AppLog.i("bubble: shown at ${params.x},${params.y} size $size")
        if (!Prefs.hintShown(context)) showHint()
    }

    fun remove() {
        if (!shown) return
        shown = false
        main.removeCallbacks(dim)
        view.spin(false)
        hideHint()
        runCatching { wm.removeView(view) }
    }

    /** Out of the way (of a picture of the screen, of the translation), or back. */
    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (hidden) hideHint() else awake()
    }

    /** Its place on the screen. */
    fun bounds(): Rect = Rect(params.x, params.y, params.x + size, params.y + size)

    val onRight: Boolean get() = params.x + size / 2 > screenSize(context).first / 2

    /** The screen turned: back to its edge, as far down as it was. */
    fun relayout() {
        if (!shown) return
        place()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun place() {
        val (w, h) = screenSize(context)
        val (right, y) = Prefs.bubble(context)
        params.x = if (right) w - size - margin else margin
        params.y = top(h) + ((bottom(h) - top(h)) * y).toInt()
    }

    private fun top(h: Int) = Ui.dp(context, 40f).coerceAtMost(h / 4)

    private fun bottom(h: Int) = (h - size - Ui.dp(context, 64f)).coerceAtLeast(top(h))

    /** Fully seen for a while after a touch, then faded a little: it sits over what is being read. */
    private fun awake() {
        main.removeCallbacks(dim)
        view.animate().cancel()
        view.alpha = 1f
        main.postDelayed(dim, 3_000)
    }

    private fun showHint() {
        val text = Ui.text(context, context.getString(R.string.bubble_hint), 14f).apply {
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 14f).toFloat())
            val p = Ui.dp(context, 12f)
            setPadding(p, Ui.dp(context, 9f), p, Ui.dp(context, 9f))
        }
        val p = Ui.overlayParams(context, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, touchable = false)
        text.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val gap = Ui.dp(context, 8f)
        p.x = if (onRight) params.x - text.measuredWidth - gap else params.x + size + gap
        p.y = params.y + (size - text.measuredHeight) / 2
        try {
            wm.addView(text, p)
        } catch (e: RuntimeException) {
            return
        }
        hint = text
        Prefs.setHintShown(context)
        main.postDelayed({ hideHint() }, 8_000)
    }

    private fun hideHint() {
        hint?.let { runCatching { wm.removeView(it) } }
        hint = null
    }

    private fun moveTo(x: Int, y: Int) {
        params.x = x
        params.y = y
        if (shown) runCatching { wm.updateViewLayout(view, params) }
    }

    /** Back to the nearest edge, where it is kept. */
    private fun settle() {
        val (w, h) = screenSize(context)
        val right = params.x + size / 2 > w / 2
        val y = params.y.coerceIn(top(h), bottom(h))
        val endX = if (right) w - size - margin else margin
        ValueAnimator.ofInt(params.x, endX).apply {
            duration = 180
            addUpdateListener { moveTo(it.animatedValue as Int, y) }
            start()
        }
        val span = (bottom(h) - top(h)).coerceAtLeast(1)
        Prefs.saveBubble(context, right, (y - top(h)).toFloat() / span)
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView : View(context) {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xe61e2025.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 1.5f).toFloat()
            color = 0x55ffffff
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 3f).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = Ui.ACCENT
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.DANGER }

        /** A globe: languages. */
        private val globe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 1.8f).toFloat()
            color = Ui.TEXT
        }
        private val meridian = RectF()
        private val oval = RectF()
        private var angle = 0f
        private var spinner: ValueAnimator? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var held = false
        private val hold = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            scaleX = 1f
            scaleY = 1f
            events.onHold()
        }

        init {
            contentDescription = context.getString(R.string.bubble_description)
        }

        fun spin(on: Boolean) {
            spinner?.cancel()
            spinner = null
            if (on) {
                spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        angle = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - ring.strokeWidth, circle)
            ring.color = if (live) Ui.LIVE else 0x55ffffff
            ring.strokeWidth = Ui.dp(context, if (live) 2.5f else 1.5f).toFloat()
            canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
            val g = width * 0.25f
            canvas.drawCircle(r, r, g, globe)
            meridian.set(r - g * 0.45f, r - g, r + g * 0.45f, r + g)
            canvas.drawOval(meridian, globe)
            canvas.drawLine(r - g, r, r + g, r, globe)
            val w = g * 0.87f
            canvas.drawLine(r - w, r - g * 0.5f, r + w, r - g * 0.5f, globe)
            canvas.drawLine(r - w, r + g * 0.5f, r + w, r + g * 0.5f, globe)
            if (spinner != null) {
                val inset = arc.strokeWidth
                oval.set(inset, inset, width - inset, height - inset)
                canvas.drawArc(oval, angle - 90f, 100f, false, arc)
            }
            if (needsSharing) canvas.drawCircle(width * 0.82f, height * 0.18f, width * 0.12f, badge)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    held = false
                    awake()
                    hideHint()
                    scaleX = 0.9f
                    scaleY = 0.9f
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                    events.onPress()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && !held && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        removeCallbacks(hold)
                        scaleX = 1f
                        scaleY = 1f
                    }
                    if (dragging) {
                        val (w, h) = screenSize(context)
                        moveTo(
                            (startX + dx.toInt()).coerceIn(0, w - size),
                            (startY + dy.toInt()).coerceIn(0, h - size),
                        )
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(hold)
                    scaleX = 1f
                    scaleY = 1f
                    when {
                        dragging -> settle()
                        !held -> {
                            performClick()
                            events.onTap()
                        }
                    }
                    awake()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    scaleX = 1f
                    scaleY = 1f
                    if (dragging) settle()
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private companion object {
        const val IDLE_ALPHA = 0.6f
    }
}

/**
 * The bubble's menu, beside it: translate the screen, automatic subtitles on or off, the language, the app,
 * and closing Hammurabi. A touch outside it closes it.
 */
class BubbleMenu(private val context: Context, private val actions: Actions) {
    interface Actions {
        fun translateScreen()
        fun setLive(on: Boolean)
        fun setRegion(region: Region)
        fun setSubtitles(on: Boolean)
        fun setTarget(code: String)
        fun openApp()
        fun quit()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val width = Ui.dp(context, 264f)
    private var root: LinearLayout? = null
    private var anchor = Rect()
    private var onRight = true

    val shown get() = root != null

    fun show(anchor: Rect, onRight: Boolean, choices: Choices, subtitlesOn: Boolean, liveOn: Boolean) {
        dismiss()
        this.anchor = anchor
        this.onRight = onRight
        val menu = frame()
        menu.addView(row(context.getString(R.string.menu_translate), null, Ui.ACCENT) {
            dismiss()
            actions.translateScreen()
        })
        menu.addView(row(
            context.getString(R.string.menu_live),
            context.getString(if (liveOn) R.string.on else R.string.off),
            if (liveOn) Ui.LIVE else Ui.TEXT,
        ) {
            dismiss()
            actions.setLive(!liveOn)
        })
        menu.addView(row(context.getString(R.string.menu_region), "${regionName(context, choices.region)} ›") {
            showRegions(choices.region)
        })
        menu.addView(row(
            context.getString(R.string.menu_subtitles),
            context.getString(if (subtitlesOn) R.string.on else R.string.off),
        ) {
            dismiss()
            actions.setSubtitles(!subtitlesOn)
        })
        menu.addView(row(context.getString(R.string.menu_language), "${Languages.name(choices.target)} ›") {
            showLanguages(choices.target)
        })
        menu.addView(row(context.getString(R.string.menu_open), null) {
            dismiss()
            actions.openApp()
        })
        menu.addView(row(context.getString(R.string.menu_quit), null, Ui.DANGER) {
            dismiss()
            actions.quit()
        })
        attach(menu)
        AppLog.i("menu: shown")
    }

    private fun showRegions(current: Region) {
        val menu = frame()
        menu.addView(row("‹  ${context.getString(R.string.menu_region)}", null) { dismiss() })
        for (r in Region.entries) {
            val name = if (r == Region.CUSTOM) context.getString(R.string.region_draw) else regionName(context, r)
            menu.addView(row(if (r == current) "✓  $name" else name, null, if (r == current) Ui.ACCENT else Ui.TEXT) {
                dismiss()
                actions.setRegion(r)
            })
        }
        attach(menu)
    }

    private fun showLanguages(current: String) {
        val menu = frame()
        menu.addView(row("‹  ${context.getString(R.string.choose_target)}", null) {
            dismiss()
        })
        val codes = Languages.byName()
        val list = object : ListView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val most = (screenSize(context).second * 0.5f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST))
            }
        }
        list.divider = null
        list.adapter = LanguageAdapter(context, codes) { current }
        list.setSelection((codes.indexOf(current) - 2).coerceAtLeast(0))
        list.setOnItemClickListener { _, _, position, _ ->
            dismiss()
            actions.setTarget(codes[position])
        }
        menu.addView(list)
        attach(menu)
    }

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun frame() = object : LinearLayout(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                return true
            }
            return super.onTouchEvent(event)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 18f).toFloat())
        val p = Ui.dp(context, 6f)
        setPadding(p, p, p, p)
        elevation = Ui.dp(context, 8f).toFloat()
    }

    private fun row(label: String, value: String?, color: Int = Ui.TEXT, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val r = Ui.dp(context, 12f).toFloat()
            background = Ui.touchable(Ui.rounded(0, r), r)
            setPadding(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), 0)
            minimumHeight = Ui.dp(context, 48f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(Ui.text(context, label, 15f, color, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) row.addView(Ui.text(context, value, 14f, Ui.TEXT_DIM))
        return row
    }

    private fun attach(menu: LinearLayout) {
        val params = Ui.overlayParams(context, width, ViewGroup.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val (w, h) = screenSize(context)
        val gap = Ui.dp(context, 8f)
        params.x = (if (onRight) anchor.left - width - gap else anchor.right + gap).coerceIn(gap, (w - width - gap).coerceAtLeast(gap))
        val height = minOf(menu.measuredHeight, (h * 0.7f).toInt())
        params.y = (anchor.centerY() - height / 2).coerceIn(Ui.dp(context, 32f), (h - height - Ui.dp(context, 48f)).coerceAtLeast(0))
        try {
            wm.addView(menu, params)
        } catch (e: RuntimeException) {
            AppLog.e("menu: not shown", e)
            return
        }
        root = menu
    }
}

/** "Bottom of the screen", in the phone's language. */
fun regionName(context: Context, r: Region): String = context.getString(
    when (r) {
        Region.BOTTOM -> R.string.region_bottom
        Region.TOP -> R.string.region_top
        Region.FULL -> R.string.region_full
        Region.CUSTOM -> R.string.region_custom
    },
)

/** The same, for the middle of a sentence: "ekranın alt kısmı". */
fun regionPhrase(context: Context, r: Region): String =
    regionName(context, r).replaceFirstChar { it.lowercase(java.util.Locale.getDefault()) }
