package dev.menzo.hammurabi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How live translation reads subtitles and dialogue; apart, to be tested on its own. */
object LiveText {
    class Line(val text: String, val box: Rect)

    /** A subtitle's or a dialogue box's lines, one under another. */
    class Group(val text: String, val box: Rect, val lineHeight: Int)

    /**
     * [lines] put together into what is read as one: lines close under each other that share their middle (a
     * film's centred subtitle) or their left side (a game's dialogue box).
     */
    fun groups(lines: List<Line>): List<Group> {
        val out = mutableListOf<MutableList<Line>>()
        for (line in lines.sortedBy { it.box.top }) {
            val h = max(1, line.box.height())
            val g = out.lastOrNull { group ->
                val last = group.last().box
                val gap = line.box.top - last.bottom
                val overlap = min(line.box.right, last.right) - max(line.box.left, last.left)
                val centred = abs(line.box.centerX() - last.centerX()) < max(line.box.width(), last.width()) / 3
                gap <= h && gap >= -h / 2 && (overlap > min(line.box.width(), last.width()) / 3 || centred)
            }
            if (g != null) g += line else out += mutableListOf(line)
        }
        return out.map { ls ->
            val box = Rect(ls[0].box)
            ls.forEach { box.union(it.box) }
            val heights = ls.map { it.box.height() }.sorted()
            Group(ScreenText.paragraph(ls.map { it.text }), box, heights[heights.size / 2])
        }
    }

    /** Letters and digits, lower case, without accents. */
    fun key(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

    /** How alike two texts are, 0 to 1, by their pairs of letters: reading the same line twice gives about 1. */
    fun similarity(a: String, b: String): Double {
        val (x, y) = key(a) to key(b)
        if (x == y) return 1.0
        if (x.length < 2 || y.length < 2) return 0.0
        val pairs = { s: String -> s.windowed(2).groupingBy { it }.eachCount() }
        val (px, py) = pairs(x) to pairs(y)
        val both = px.entries.sumOf { (k, n) -> minOf(n, py[k] ?: 0) }
        return 2.0 * both / (x.length - 1 + y.length - 1)
    }

    /** One begins as the other does and goes further: dialogue being written out letter by letter. */
    private fun grows(longer: String, shorter: String): Boolean =
        longer.length > shorter.length && shorter.length >= 2 && longer.startsWith(shorter.dropLast(2))

    /** The same text read again (a letter misread over a moving picture aside), not one still being written out. */
    fun same(a: String, b: String): Boolean {
        val (x, y) = key(a) to key(b)
        if (x == y) return x.isNotEmpty()
        if (grows(x, y) || grows(y, x)) return false
        return similarity(a, b) >= 0.85 && abs(x.length - y.length) <= max(x.length, y.length) * 0.12
    }

    /** [now] is another text than [before]: a new subtitle, or the dialogue written out further. */
    fun changed(now: String, before: String): Boolean {
        val (x, y) = key(now) to key(before)
        if (x == y) return false
        if (x.length >= y.length + 3 && grows(x, y)) return true
        return similarity(now, before) < 0.8 || x.length > y.length * 1.2 + 2
    }

    /**
     * ALL CAPITALS, as games and some subtitles write, as a sentence is written: translators take capitals for
     * names and abbreviations.
     */
    fun sentenceCase(text: String, locale: Locale): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4 || letters.count { it.isUpperCase() } < letters.length * 0.8) return text
        val low = text.lowercase(locale)
        val out = StringBuilder(low.length)
        var start = true
        for ((i, c) in low.withIndex()) {
            if (start && c.isLetter()) {
                out.append(c.titlecase(locale))
                start = false
            } else {
                out.append(c)
            }
            if (c == '.' || c == '!' || c == '?' || c == '\n') start = true
            // English's "I" stays a capital.
            if (locale.language == "en" && c == 'i' && (i == 0 || !low[i - 1].isLetter()) &&
                (i + 1 == low.length || !low[i + 1].isLetter() || low[i + 1] == '\'')
            ) {
                out.setCharAt(out.length - 1, 'I')
            }
        }
        return out.toString()
    }
}

/**
 * Live translation, for videos and games: the watched part of the screen read over and over; text that stays
 * the same for two readings (a subtitle, or dialogue written out letter by letter, once it is all there) is
 * translated, and shown until it changes or goes. Text already in the target language shows nothing. The
 * translations' own boxes ([ours]) are left out of what is read.
 */
class LiveTranslator(private val context: Context, private val listener: Listener) : ScreenCapture.LiveSink {
    /** A translation, and the screen area of the text it translates. */
    class Shown(val box: Rect, val translation: String, val original: String)

    /** Told on the main thread. */
    interface Listener {
        fun onShow(items: List<Shown>)
        fun onHide()

        /** The screen is black: the app in front forbids capture. */
        fun onBlack()
        fun onDownloading(lang: String)
    }

    @Volatile
    var target = "en"

    @Volatile
    var script = Script.LATIN

    /** Fractions of the screen: left, top, right, bottom. */
    @Volatile
    var area = floatArrayOf(0f, 0.5f, 1f, 1f)

    /** The status bar's height: its text is not read. */
    @Volatile
    var skipTop = 0

    /** Where the translations are on the screen, in its pixels. */
    @Volatile
    var ours: List<Rect> = emptyList()

    /** Paused while the whole screen is translated or an area is being drawn. */
    @Volatile
    var paused = false

    @Volatile
    private var busy = false

    @Volatile
    private var closed = false

    @Volatile
    private var resetAsked = false

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hm-live") }
    private val main = Handler(Looper.getMainLooper())

    // On the worker only.
    private var lastKey = ""
    private var stable = 0
    private var handled = ""
    private var visible = false
    private var empty = 0
    private var blackSince = 0L
    private var toldBlack = false
    private var lastLang: String? = null
    private var lastArea = Rect()
    private var lastGroups: List<LiveText.Group> = emptyList()
    private val downloaded = HashSet<String>()
    private var generation = 0

    override fun ready() = !busy && !paused && !closed

    override fun area(width: Int, height: Int): Rect {
        val a = area
        return Rect(
            (a[0] * width).toInt(),
            max((a[1] * height).toInt(), skipTop),
            (a[2] * width).toInt(),
            (a[3] * height).toInt(),
        )
    }

    override fun frame(bitmap: Bitmap?, area: Rect, black: Boolean) {
        busy = true
        worker.execute {
            try {
                if (!closed && !paused) work(bitmap, area, black)
            } catch (e: Exception) {
                if (!closed) AppLog.w("live: a picture failed", e)
            } finally {
                bitmap?.recycle()
                busy = false
            }
        }
    }

    /** Forget what was read: the screen turned, or the language changed. */
    fun reset() {
        resetAsked = true
    }

    fun close() {
        closed = true
        worker.shutdown()
    }

    private fun post(r: () -> Unit) {
        main.post { if (!closed) r() }
    }

    private fun work(bitmap: Bitmap?, area: Rect, black: Boolean) {
        if (resetAsked) {
            resetAsked = false
            lastKey = ""
            stable = 0
            handled = ""
            lastGroups = emptyList()
            lastArea = Rect()
            if (visible) hide("reset")
        }
        val now = SystemClock.uptimeMillis()
        if (black) {
            if (blackSince == 0L) blackSince = now
            if (!toldBlack && now - blackSince >= BLACK_MS) {
                toldBlack = true
                AppLog.i("live: the screen is black")
                post { listener.onBlack() }
            }
            if (visible) hide("black")
            return
        }
        blackSince = 0L
        toldBlack = false

        // The screen has not changed: it reads as it did.
        var slow = false
        val groups = if (bitmap == null) {
            if (area != lastArea) return
            lastGroups
        } else {
            // Read smaller: subtitles and dialogue are large text, and reading takes about half as long.
            val scale = if (bitmap.width > READ_WIDTH) READ_WIDTH.toFloat() / bitmap.width else 1f
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, READ_WIDTH, max(1, (bitmap.height * scale).toInt()), true)
            } else {
                bitmap
            }
            val started = SystemClock.uptimeMillis()
            val text = try {
                Tasks.await(
                    // Long enough for the reader's first load on a slow phone.
                    Recognizers.get(script).process(InputImage.fromBitmap(small, 0)), 30, TimeUnit.SECONDS,
                )
            } finally {
                if (small !== bitmap) small.recycle()
            }
            slow = SystemClock.uptimeMillis() - started > SLOW_MS
            val mine = ours
            val lines = text.textBlocks.flatMap { b ->
                b.lines.mapNotNull { l ->
                    val box = l.boundingBox ?: return@mapNotNull null
                    val r = Rect(
                        (box.left / scale).toInt(), (box.top / scale).toInt(),
                        (box.right / scale).toInt(), (box.bottom / scale).toInt(),
                    )
                    r.offset(area.left, area.top)
                    LiveText.Line(l.text, r)
                }
            }.filter { line -> ScreenText.worthTranslating(line.text) && mine.none { mostlyIn(line.box, it) } }
            LiveText.groups(lines)
        }
        lastGroups = groups
        lastArea = Rect(area)

        if (groups.isEmpty()) {
            empty++
            lastKey = ""
            stable = 0
            if (empty >= 2) {
                handled = ""
                if (visible) hide("no text")
            }
            return
        }
        empty = 0
        val key = groups.joinToString("\n") { it.text }
        if (handled.isNotEmpty() && LiveText.changed(key, handled)) {
            handled = ""
            if (visible) hide("new text")
        }
        if (LiveText.same(key, lastKey)) {
            stable++
        } else {
            if (key != lastKey) AppLog.i("live: reading \"${key.replace('\n', '|').take(120)}\"")
            lastKey = key
            // A phone this slow reads a subtitle once while it shows: once has to do. Dialogue still being
            // written out is translated again when it reads longer.
            stable = if (slow) 2 else 1
        }
        if (stable < 2 || handled.isNotEmpty()) return
        translate(groups, key)
    }

    private fun translate(groups: List<LiveText.Group>, key: String) {
        val to = target
        val items = mutableListOf<Shown>()
        for (g in groups) {
            val raw = g.text
            var lang = if (ScreenText.isShort(raw)) ScreenText.UNDETERMINED else identify(raw)
            if (lang == ScreenText.UNDETERMINED) lang = lastLang ?: identify(groups.joinToString(". ") { it.text })
            if (lang == ScreenText.UNDETERMINED) continue
            if (!ScreenText.isShort(raw)) lastLang = lang
            if (lang == to) continue
            val code = Languages.supported(lang) ?: continue
            if (code !in downloaded || to !in downloaded) {
                val haveFrom = Translations.isDownloaded(code)
                if (!haveFrom || !Translations.isDownloaded(to)) {
                    val missing = if (haveFrom) to else code
                    post { listener.onDownloading(missing) }
                }
                try {
                    Translations.ensure(code, to)
                } catch (e: Exception) {
                    AppLog.w("live: $code → $to model not downloaded", e)
                    continue
                }
                downloaded += code
                downloaded += to
            }
            val input = LiveText.sentenceCase(raw, Locale.forLanguageTag(code))
            val tr = try {
                Translations.translate(input, code, to)
            } catch (e: Exception) {
                AppLog.w("live: not translated", e)
                continue
            }
            if (ScreenText.onlyRepeats(tr, raw) || ScreenText.onlyRepeats(tr, input)) continue
            items += Shown(g.box, tr, raw)
        }
        handled = key
        if (items.isEmpty()) return
        AppLog.i("live: ${items.size} translated (${items.joinToString(" | ") { "${it.original} → ${it.translation}" }.take(300)})")
        visible = true
        val gen = ++generation
        post { if (gen == generation) listener.onShow(items) }
    }

    private fun hide(why: String) {
        AppLog.i("live: hidden ($why)")
        visible = false
        val gen = ++generation
        post { if (gen == generation) listener.onHide() }
    }

    private fun identify(text: String): String = try {
        val tag = Tasks.await(Recognizers.languages.identifyLanguage(text), 5, TimeUnit.SECONDS)
        if (tag.endsWith("-Latn", ignoreCase = true)) ScreenText.UNDETERMINED else tag
    } catch (e: Exception) {
        ScreenText.UNDETERMINED
    }

    /** A line mostly inside one of the translations' boxes is the translation, read back. */
    private fun mostlyIn(line: Rect, box: Rect): Boolean {
        val i = Rect()
        if (!i.setIntersect(line, box)) return false
        return i.width().toLong() * i.height() * 2 >= line.width().toLong() * line.height()
    }

    private companion object {
        const val BLACK_MS = 3_000L

        /** Pictures wider than this are read at this width. */
        const val READ_WIDTH = 720

        /** A reading longer than this: the phone reads too slowly to read everything twice. */
        const val SLOW_MS = 1_500L
    }
}

/**
 * Live translations on the screen, each in a film subtitle's box right over the text it translates (under it
 * when there is no room above), fitted to the screen. Touches go through them to the video or the game.
 */
class LiveOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = mutableListOf<TextView>()
    private val params = mutableListOf<WindowManager.LayoutParams>()

    /** The subtitles' size: 0 small, 1 medium, 2 large. */
    var size = 1

    /**
     * Where a window lands on the screen compared with where it is put: on some Android versions below the
     * status bar. Learned from the first box shown, so the boxes sit where they are meant to.
     */
    private var dx = 0
    private var dy = 0
    private val at = IntArray(2)

    /** Shows [items]; returns where their boxes are, in the screen's pixels. */
    fun show(items: List<LiveTranslator.Shown>): List<Rect> {
        val (sw, sh) = screenSize(context)
        val rects = mutableListOf<Rect>()
        val gap = Ui.dp(context, 6f)
        val margin = Ui.dp(context, 6f)
        val top = Ui.dp(context, 28f)
        items.take(MAX).forEachIndexed { i, item ->
            val v = view(i) ?: return@forEachIndexed
            val maxWidth = min(sw * 0.94f, max(item.box.width() * 1.3f, sw * 0.6f)).toInt()
            v.maxWidth = maxWidth
            v.text = item.translation
            // Fitted: at most four lines, smaller when longer.
            var sp = BIG[size.coerceIn(0, 2)]
            while (true) {
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                v.measure(
                    View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                if (v.lineCount <= MAX_LINES || sp <= LEAST) break
                sp = max(LEAST, sp - 1.5f)
            }
            val w = v.measuredWidth
            val h = v.measuredHeight
            val x = (item.box.centerX() - w / 2).coerceIn(margin, max(margin, sw - w - margin))
            val above = item.box.top - gap - h
            val y = if (above >= top) above else min(item.box.bottom + gap, max(top, sh - h - margin))
            val p = params[i]
            p.x = x - dx
            p.y = y - dy
            runCatching { wm.updateViewLayout(v, p) }
            v.visibility = View.VISIBLE
            rects += Rect(x, y, x + w, y + h)
            // Once it has moved there: where it really is.
            v.postDelayed({
                if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) return@postDelayed
                v.getLocationOnScreen(at)
                val ex = at[0] - x
                val ey = at[1] - y
                if (ex != 0 || ey != 0) {
                    dx += ex
                    dy += ey
                    AppLog.i("live: boxes land ${dx},$dy off; put right")
                    p.x = x - dx
                    p.y = y - dy
                    runCatching { wm.updateViewLayout(v, p) }
                }
            }, 250)
        }
        for (j in items.size until views.size) views[j].visibility = View.INVISIBLE
        return rects
    }

    fun hide() {
        views.forEach { it.visibility = View.INVISIBLE }
    }

    fun remove() {
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
        params.clear()
    }

    private fun view(i: Int): TextView? {
        if (i < views.size) return views[i]
        val v = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.08f)
            setShadowLayer(4f, 0f, 1f, 0xff000000.toInt())
            val p = Ui.dp(context, 12f)
            setPadding(p, Ui.dp(context, 6f), p, Ui.dp(context, 7f))
            background = Ui.rounded(0xe6000000.toInt(), Ui.dp(context, 10f).toFloat())
            visibility = View.INVISIBLE
        }
        val p = Ui.overlayParams(context, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, touchable = false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        try {
            wm.addView(v, p)
        } catch (e: RuntimeException) {
            AppLog.e("live: box not shown", e)
            return null
        }
        views += v
        params += p
        return v
    }

    private companion object {
        const val MAX = 3
        const val MAX_LINES = 4
        const val LEAST = 12f
        val BIG = floatArrayOf(16f, 19f, 23f)
    }
}

/**
 * Drawing the part of the screen live translation watches, over the app it is for: a finger drags a
 * rectangle; Save keeps it, Cancel or Back leaves the old one.
 */
class RegionPicker(
    private val context: Context,
    start: FloatArray,
    private val done: (FloatArray?) -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = PickerView()
    private var shown = false
    private val size = screenSize(context)

    /** The rectangle, in the screen's pixels. */
    private val rect = RectF(start[0] * size.first, start[1] * size.second, start[2] * size.first, start[3] * size.second)

    fun show() {
        val params = Ui.overlayParams(
            context, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            touchable = true, focusable = true,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(view, params)
            shown = true
            AppLog.i("live: drawing the area")
        } catch (e: RuntimeException) {
            AppLog.e("live: area picker not shown", e)
            done(null)
        }
    }

    /** Gone without keeping anything: the screen turned, or Hammurabi stops. */
    fun cancel() = finish(false)

    private fun finish(save: Boolean) {
        if (!shown) return
        shown = false
        runCatching { wm.removeView(view) }
        val (w, h) = size
        done(
            if (save) {
                floatArrayOf(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h).map { it.coerceIn(0f, 1f) }.toFloatArray()
            } else {
                null
            },
        )
    }

    @SuppressLint("ViewConstructor")
    private inner class PickerView : FrameLayout(context) {
        private val shade = Paint().apply { color = 0x99000000.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 2.5f).toFloat()
            color = Ui.ACCENT
        }
        private val at = IntArray(2)
        private var downX = 0f
        private var downY = 0f
        private val before = RectF()

        init {
            setWillNotDraw(false)
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 20f).toFloat())
                val p = Ui.dp(context, 14f)
                setPadding(p, p, p, p)
                isClickable = true
            }
            panel.addView(Ui.text(context, context.getString(R.string.area_hint), 15f, bold = true))
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.addView(Ui.button(context, context.getString(R.string.cancel)) { finish(false) })
            buttons.addView(
                Ui.button(context, context.getString(R.string.save), Ui.ACCENT) { finish(true) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Ui.dp(context, 8f)
                },
            )
            panel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(context, 10f)
            })
            // Out of the way of the area: at the top when the area is low on the screen.
            val low = rect.centerY() > size.second / 2
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (low) Gravity.TOP else Gravity.BOTTOM).apply {
                val m = Ui.dp(context, 16f)
                setMargins(m, Ui.dp(context, 48f), m, Ui.dp(context, 48f))
            })
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestFocus()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(at)
            val r = RectF(rect).apply { offset(-at[0].toFloat(), -at[1].toFloat()) }
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, r.top, shade)
            canvas.drawRect(0f, r.bottom, w, h, shade)
            canvas.drawRect(0f, r.top, r.left, r.bottom, shade)
            canvas.drawRect(r.right, r.top, w, r.bottom, shade)
            canvas.drawRect(r.left, r.top, r.right, r.bottom, edge)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.rawX
            val y = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y
                    before.set(rect)
                }
                MotionEvent.ACTION_MOVE -> {
                    rect.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Too small to hold a line of text: a tap, not an area.
                    val least = Ui.dp(context, 40f)
                    if (rect.width() < least || rect.height() < least / 2) rect.set(before)
                    invalidate()
                }
            }
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) finish(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
