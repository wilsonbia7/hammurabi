package dev.menzo.hammurabi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The screen translated in place, as Google Lens does: each block of text read is covered with its
 * translation, in the block's own colours, while the rest of the screen stays as it is. Tapping a
 * translation shows it with its original, to read or copy; tapping anywhere else, or Back, closes it. A
 * panel says how it goes and holds the language, "Original", "Copy" and close, with Google's attribution.
 *
 * Shown empty first ([show]): nothing of it is on the picture of the screen taken then.
 */
class TranslationLayer(
    private val context: Context,
    private var target: String,
    private val onClosed: () -> Unit,
    private val onLanguage: (String) -> Unit,
) : ScreenTranslator.Listener {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val dp = { v: Float -> Ui.dp(context, v) }
    private val view = LayerView()
    private var shown = false
    private var picture: Bitmap? = null

    /** The picture's pixels to the layer's: the picture is of the whole screen, at its size. */
    private var scale = 1f
    private val at = IntArray(2)

    private val patches = mutableListOf<Patch>()
    private var showingOriginal = false
    private var done = false
    private var translated = 0
    private var toTranslate = 0

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SURFACE, dp(20f).toFloat())
        setPadding(dp(14f), dp(12f), dp(14f), dp(10f))
        isClickable = true
        visibility = View.GONE
    }
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
    }
    private val status = Ui.text(context, "", 15f, bold = true)
    private val hint = Ui.text(context, "", 13f, Ui.TEXT_DIM)
    private val languageButton = Ui.button(context, "") { toggleLanguages() }
    private val originalButton = Ui.button(context, context.getString(R.string.layer_original)) { toggleOriginal() }
    private val copyButton = Ui.button(context, context.getString(R.string.copy)) { copyAll() }
    private val closeButton = Ui.button(context, "✕") { close() }.apply {
        contentDescription = context.getString(R.string.close)
    }

    /** A translation tapped: its original and translation, to read or copy. */
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SURFACE, dp(20f).toFloat())
        setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        isClickable = true
        visibility = View.GONE
    }
    private val cardFrom = Ui.text(context, "", 12f, Ui.TEXT_DIM, bold = true)
    private val cardOriginal = Ui.text(context, "", 15f, Ui.TEXT_DIM).apply { setTextIsSelectable(true) }
    private val cardTo = Ui.text(context, "", 12f, Ui.TEXT_DIM, bold = true)
    private val cardTranslation = Ui.text(context, "", 18f, bold = true).apply { setTextIsSelectable(true) }
    private var cardPatch: Patch? = null

    /** The languages to translate into. */
    private val languages = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SURFACE, dp(20f).toFloat())
        setPadding(dp(8f), dp(12f), dp(8f), dp(8f))
        isClickable = true
        visibility = View.GONE
    }

    /** The panel and cards, at the bottom of the screen or, when more text is there, the top. */
    private val dock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        buildPanel()
        buildCard()
        buildLanguages()
        dock.addView(card, block(bottom = 8f))
        dock.addView(languages, block(bottom = 8f))
        dock.addView(panel, block())
        view.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun block(bottom: Float = 0f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(bottom) }

    private fun buildPanel() {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(spinner, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(10f) })
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(top)
        panel.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
        })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) } }
        buttons.addView(languageButton)
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(originalButton, gap())
        buttons.addView(copyButton, gap())
        buttons.addView(closeButton, gap())
        panel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })
        // Google's terms: the attribution next to every translation.
        panel.addView(
            Ui.text(context, context.getString(R.string.attribution), 11f, Ui.TEXT_DIM).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            },
        )
        languageButton.text = languageLabel()
    }

    private fun buildCard() {
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(cardFrom)
        texts.addView(cardOriginal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2f)
            bottomMargin = dp(12f)
        })
        texts.addView(cardTo)
        texts.addView(cardTranslation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2f)
        })
        val scroll = LimitedScroll(0.4f).apply { addView(texts) }
        card.addView(scroll)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Ui.button(context, context.getString(R.string.copy)) {
            cardPatch?.let { p -> Ui.copy(context, "translation", p.block.translation ?: p.block.text) }
        })
        buttons.addView(Ui.button(context, context.getString(R.string.close)) { hideCards() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })
    }

    private fun buildLanguages() {
        languages.addView(Ui.text(context, context.getString(R.string.choose_target), 15f, bold = true).apply {
            setPadding(dp(8f), 0, dp(8f), dp(8f))
        })
        val codes = Languages.byName()
        val list = object : ListView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val most = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST))
            }
        }
        list.divider = null
        list.adapter = LanguageAdapter(context, codes) { target }
        list.setOnItemClickListener { _, _, position, _ ->
            val code = codes[position]
            hideCards()
            if (code != target) {
                target = code
                languageButton.text = languageLabel()
                onLanguage(code)
            }
        }
        languages.addView(list)
    }

    private fun languageLabel() = "${Languages.name(target)} ▾"

    /** Shows the layer empty; [ready] once it is on the screen, with the bubble gone. */
    fun show(ready: () -> Unit) {
        if (shown) return
        val params = Ui.overlayParams(
            context, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            touchable = true, focusable = true,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(view, params)
        } catch (e: RuntimeException) {
            AppLog.e("layer: not shown", e)
            onClosed()
            return
        }
        shown = true
        var called = false
        val once = Runnable {
            if (!called && shown) {
                called = true
                ready()
            }
        }
        view.viewTreeObserver.registerFrameCommitCallback(once)
        // Should the commit not be told (no drawing, say), the picture is taken anyway.
        main.postDelayed(once, 300)
    }

    /** The screen as it was taken: the panel shows, and the reading starts. */
    fun picture(bitmap: Bitmap, screenWidth: Int) {
        picture = bitmap
        scale = screenWidth.toFloat() / bitmap.width
        reading()
    }

    /** Reading anew, in another language. */
    fun reading() {
        patches.clear()
        done = false
        translated = 0
        toTranslate = 0
        cardPatch = null
        Ui.gone(panel, false)
        setStatus(context.getString(R.string.layer_reading), busy = true)
        hint.text = ""
        Ui.gone(hint, true)
        view.invalidate()
    }

    override fun onRead(blocks: List<ScreenTranslator.Block>) {
        view.getLocationOnScreen(at)
        patches.clear()
        // Text much taller than most of the screen's is a title: its translation is bold.
        val usual = blocks.map { it.lineHeight }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        blocks.forEach { patches += Patch(it, mapped(it), bold = it.lineHeight >= usual * 1.3f) }
        if (blocks.isEmpty()) {
            finish(context.getString(R.string.layer_nothing), null)
            return
        }
        placeDock()
        setStatus(context.getString(R.string.layer_translating), busy = true)
        view.invalidate()
    }

    override fun onDownloading(lang: String) {
        setStatus(context.getString(R.string.layer_downloading, Languages.name(lang)), busy = true)
        hint.text = context.getString(R.string.layer_downloading_note)
        Ui.gone(hint, false)
    }

    override fun onTranslated(block: ScreenTranslator.Block) {
        val patch = patches.firstOrNull { it.block === block } ?: return
        style(patch)
        translated++
        if (toTranslate == 0) {
            toTranslate = patches.count { p -> p.block.lang.let { it != null && it != target && Languages.supported(it) != null } }
        }
        setStatus(context.getString(R.string.layer_progress, translated, max(translated, toTranslate)), busy = true)
        Ui.gone(hint, true)
        view.invalidate()
    }

    override fun onDone(summary: ScreenTranslator.Summary) {
        // Blocks with no translation are left as they are: in the language already, or not translated.
        patches.forEach { if (it.block.translation == null) it.skip = true }
        val text = when {
            summary.read == 0 -> context.getString(R.string.layer_nothing)
            summary.translated == 0 && summary.same > 0 && summary.unsupported.isEmpty() ->
                context.getString(R.string.layer_all_same, Languages.name(target))
            summary.translated == 0 && summary.unsupported.isNotEmpty() ->
                context.getString(R.string.layer_unsupported, summary.unsupported.joinToString { Languages.name(it) })
            summary.translated == 0 -> context.getString(R.string.layer_nothing)
            else -> context.resources.getQuantityString(R.plurals.layer_done, summary.translated, summary.translated) +
                " · " + summary.from.joinToString { Languages.name(it) } + " → " + Languages.name(target)
        }
        finish(text, if (summary.translated > 0) context.getString(R.string.layer_hint) else null)
    }

    override fun onError(message: String) {
        patches.forEach { if (it.block.translation == null) it.skip = true }
        finish(message, null, error = true)
    }

    private fun finish(text: String, hintText: String?, error: Boolean = false) {
        done = true
        setStatus(text, busy = false, error = error)
        hint.text = hintText ?: ""
        Ui.gone(hint, hintText == null)
        view.invalidate()
    }

    private fun setStatus(text: String, busy: Boolean, error: Boolean = false) {
        Ui.gone(panel, false)
        status.text = text
        status.setTextColor(if (error) Ui.DANGER else Ui.TEXT)
        Ui.gone(spinner, !busy)
    }

    fun close() {
        if (!shown) return
        shown = false
        runCatching { wm.removeView(view) }
        // Not recycled: the reader may still be reading it; it goes with the layer.
        picture = null
        onClosed()
    }

    /** The block's place on the layer, a little wider than its text. */
    private fun mapped(b: ScreenTranslator.Block): RectF {
        val pad = dp(2f)
        return RectF(
            b.box.left * scale - at[0] - pad,
            b.box.top * scale - at[1] - pad,
            b.box.right * scale - at[0] + pad,
            b.box.bottom * scale - at[1] + pad,
        )
    }

    /** The panel goes where there is less text: the bottom, unless more is there than at the top. */
    private fun placeDock() {
        val h = view.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val near = { top: Boolean ->
            patches.sumOf { p ->
                val r = p.rect
                val inside = if (top) min(r.bottom, h * 0.3f) - max(r.top, 0f) else min(r.bottom, h.toFloat()) - max(r.top, h * 0.7f)
                (max(0f, inside) * r.width()).toDouble()
            }
        }
        val top = near(false) > near(true) * 1.5
        (dock.layoutParams as FrameLayout.LayoutParams).gravity = if (top) Gravity.TOP else Gravity.BOTTOM
        // The panel nearest the screen's edge, the cards toward the middle.
        dock.removeAllViews()
        if (top) {
            dock.addView(panel, block(bottom = 8f))
            dock.addView(card, block(bottom = 8f))
            dock.addView(languages, block())
        } else {
            dock.addView(card, block(bottom = 8f))
            dock.addView(languages, block(bottom = 8f))
            dock.addView(panel, block())
        }
        dock.requestLayout()
    }

    /** Colours and text size for [p]'s translation, from the picture around and inside its block. */
    private fun style(p: Patch) {
        val translation = p.block.translation ?: return
        if (ScreenText.onlyRepeats(translation, p.block.text)) {
            // It says the same: the original stays.
            p.skip = true
            return
        }
        val pic = picture
        p.background = pic?.let { backgroundOf(it, p.block) } ?: Color.WHITE
        p.foreground = pic?.let { foregroundOf(it, p.block, p.background) } ?: Color.BLACK
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.foreground
            if (p.bold) typeface = Typeface.DEFAULT_BOLD
        }
        val padX = dp(3f).toFloat()
        val padY = dp(1f).toFloat()
        val viewW = (view.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels).toFloat()
        val viewH = (view.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels).toFloat()
        val rect = RectF(p.rect)
        val least = Ui.sp(context, 10f)
        val line = p.block.lineHeight * scale
        // A font's line is taller than the box of its letters: the patch may grow by half a line, so the
        // translation keeps about the original's size; past that it shrinks to fit.
        val room = rect.height() + line * 0.5f
        var size = min(line * 1.12f, Ui.sp(context, 44f)).coerceAtLeast(least)
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = layoutOf(translation, paint, (rect.width() - 2 * padX).toInt())
            if (layout.height + 2 * padY <= room || size <= least) break
            size = max(least, size * 0.92f)
        }
        if (layout.height + 2 * padY > room) {
            // Still too big at the smallest size: a single line widens first.
            val wide = paint.measureText(translation) + 2 * padX
            if (p.block.lines == 1 && wide > rect.width()) {
                rect.right = min(viewW - dp(4f), rect.left + wide)
                if (rect.width() < wide) rect.left = max(dp(4f).toFloat(), rect.right - wide)
                layout = layoutOf(translation, paint, (rect.width() - 2 * padX).toInt())
            }
        }
        // Taller than the block: it grows about its middle.
        val need = layout.height + 2 * padY
        if (need > rect.height()) {
            rect.top = max(0f, rect.top - (need - rect.height()) / 2)
            rect.bottom = min(viewH, rect.top + need)
        }
        p.rect.set(rect)
        p.layout = layout
        p.pad = padX
    }

    private fun layoutOf(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    /** The colour around the block: the middle value of each channel along a ring just outside it. */
    private fun backgroundOf(pic: Bitmap, b: ScreenTranslator.Block): Int {
        val r = b.box
        val o = 3
        val xs = (0..8).map { r.left + (r.width() * it) / 8 }
        val ys = (0..4).map { r.top + (r.height() * it) / 4 }
        val points = xs.flatMap { x -> listOf(x to r.top - o, x to r.bottom + o) } +
            ys.flatMap { y -> listOf(r.left - o to y, r.right + o to y) }
        val colors = points.map { (x, y) -> pic.getPixel(x.coerceIn(0, pic.width - 1), y.coerceIn(0, pic.height - 1)) }
        val mid = { channel: (Int) -> Int -> colors.map(channel).sorted()[colors.size / 2] }
        return Color.rgb(mid { Color.red(it) }, mid { Color.green(it) }, mid { Color.blue(it) })
    }

    /** The text's colour: what inside the block differs most from [bg]; black or white when that is too faint. */
    private fun foregroundOf(pic: Bitmap, b: ScreenTranslator.Block, bg: Int): Int {
        val r = b.box
        var best = bg
        var far = -1
        for (i in 1 until 16) for (j in 1 until 6) {
            val c = pic.getPixel(
                (r.left + r.width() * i / 16).coerceIn(0, pic.width - 1),
                (r.top + r.height() * j / 6).coerceIn(0, pic.height - 1),
            )
            val d = abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) + abs(Color.blue(c) - Color.blue(bg))
            if (d > far) {
                far = d
                best = c
            }
        }
        return if (contrast(best, bg) >= 3.0) best else if (luminance(bg) > 0.4) 0xff1f1f1f.toInt() else Color.WHITE
    }

    private fun luminance(c: Int): Double {
        val ch = { v: Int ->
            val s = v / 255.0
            if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private fun toggleOriginal() {
        showingOriginal = !showingOriginal
        originalButton.text = context.getString(if (showingOriginal) R.string.layer_translation else R.string.layer_original)
        view.invalidate()
    }

    private fun toggleLanguages() {
        val open = languages.visibility != View.VISIBLE
        hideCards()
        Ui.gone(languages, !open)
    }

    private fun copyAll() {
        // Top to bottom, as the screen reads: translations, and what needed none as it is.
        val text = patches.joinToString("\n\n") { it.block.translation ?: it.block.text }
        if (text.isNotBlank()) Ui.copy(context, "translation", text)
    }

    private fun open(p: Patch) {
        cardPatch = p
        val lang = p.block.lang?.takeIf { it != ScreenText.UNDETERMINED }
        cardFrom.text = lang?.let { Languages.name(it) } ?: context.getString(R.string.layer_original)
        cardOriginal.text = p.block.text
        cardTo.text = Languages.name(target)
        cardTranslation.text = p.block.translation ?: ""
        Ui.gone(cardTo, p.block.translation == null)
        Ui.gone(cardTranslation, p.block.translation == null)
        Ui.gone(languages, true)
        Ui.gone(card, false)
    }

    private fun hideCards(): Boolean {
        val any = card.visibility == View.VISIBLE || languages.visibility == View.VISIBLE
        Ui.gone(card, true)
        Ui.gone(languages, true)
        cardPatch = null
        return any
    }

    private fun patchAt(x: Float, y: Float): Patch? =
        patches.lastOrNull { !it.skip && it.layout != null && !showingOriginal && it.rect.contains(x, y) }

    /** A translation's place, colours and text. */
    private class Patch(val block: ScreenTranslator.Block, val rect: RectF, val bold: Boolean) {
        var background = Color.WHITE
        var foreground = Color.BLACK
        var layout: StaticLayout? = null
        var pad = 0f

        /** Not covered: no translation, or one that says the same. */
        var skip = false
    }

    @SuppressLint("ViewConstructor")
    private inner class LayerView : FrameLayout(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pending = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f).toFloat()
            color = 0xaa7aa7ff.toInt()
        }
        private val pendingFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x227aa7ff }
        private val radius = dp(4f).toFloat()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var pressed: Patch? = null
        private val longPress = Runnable {
            val p = pressed ?: return@Runnable
            pressed = null
            p.block.translation?.let {
                Ui.copy(context, "translation", it)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }
        }

        init {
            setWillNotDraw(false)
            isFocusable = true
            isFocusableInTouchMode = true
            // Focused for Back, it would be greyed over by Android's focus highlight, and so would the
            // picture of the screen taken through it.
            defaultFocusHighlightEnabled = false
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
                } else {
                    @Suppress("DEPRECATION")
                    intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                val m = dp(12f)
                dock.setPadding(bars[0] + m, bars[1] + m, bars[2] + m, bars[3] + m)
                insets
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestFocus()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (showingOriginal) return
            for (p in patches) {
                if (p.skip) continue
                val layout = p.layout
                if (layout == null) {
                    // Not translated yet.
                    if (!done) {
                        canvas.drawRoundRect(p.rect, radius, radius, pendingFill)
                        canvas.drawRoundRect(p.rect, radius, radius, pending)
                    }
                    continue
                }
                fill.color = p.background
                canvas.drawRoundRect(p.rect, radius, radius, fill)
                canvas.save()
                canvas.translate(p.rect.left + p.pad, p.rect.top + max(0f, (p.rect.height() - layout.height) / 2))
                layout.draw(canvas)
                canvas.restore()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    pressed = patchAt(event.x, event.y)
                    if (pressed != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    removeCallbacks(longPress)
                    pressed = null
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    val moved = abs(event.x - downX) > slop || abs(event.y - downY) > slop
                    val hit = pressed
                    pressed = null
                    if (!moved) {
                        when {
                            hit != null -> open(hit)
                            hideCards() -> {}
                            else -> close()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    pressed = null
                }
            }
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !hideCards()) close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** A scroll view no taller than [part] of the screen. */
    @SuppressLint("ViewConstructor")
    private inner class LimitedScroll(private val part: Float) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val most = (context.resources.displayMetrics.heightPixels * part).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST))
        }
    }
}

/** The languages to choose from, in light text on the dark windows, the chosen one marked. */
class LanguageAdapter(
    private val context: Context,
    private val codes: List<String>,
    private val chosen: () -> String?,
) : BaseAdapter() {
    override fun getCount() = codes.size

    override fun getItem(position: Int) = codes[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = (convertView as? TextView) ?: Ui.text(context, "", 16f).apply {
            val p = Ui.dp(context, 12f)
            setPadding(p, p, p, p)
            background = Ui.touchable(Ui.rounded(0, Ui.dp(context, 12f).toFloat()), Ui.dp(context, 12f).toFloat())
        }
        val code = codes[position]
        val on = code == chosen()
        v.text = if (on) "✓  ${Languages.name(code)}" else Languages.name(code)
        v.setTextColor(if (on) Ui.ACCENT else Ui.TEXT)
        return v
    }
}
