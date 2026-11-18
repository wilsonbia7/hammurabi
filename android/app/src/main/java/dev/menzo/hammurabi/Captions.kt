package dev.menzo.hammurabi

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONObject
import java.text.Normalizer

/** Hammurabi's events (the engine's, the service's), handed out on the main thread to whoever shows them. */
object CaptionBus {
    private val listeners = mutableListOf<(JSONObject) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** The last state, status and speed, for a screen that opens while subtitles run. */
    val last = mutableMapOf<String, JSONObject>()

    fun add(listener: (JSONObject) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (JSONObject) -> Unit) {
        listeners -= listener
    }

    /** [emit] from another thread. */
    fun post(event: JSONObject) {
        main.post { emit(event) }
    }

    /** Main thread only. */
    fun emit(event: JSONObject) {
        val t = event.optString("t")
        if (t == "state" || t == "status" || t == "stats" || t == "progress") last[t] = event
        listeners.toList().forEach { it(event) }
    }
}

/**
 * The captions on screen: the last few sentences and the one still being heard, kept as the desktop
 * overlay keeps them (2 cues), each long enough to read after it last changed: [holdMs] at least.
 */
class Captions(private val cues: Int = 2, private val holdMs: Long = 4_000) {
    private class Cue(val original: String, val translated: String?, val same: Boolean, val at: Long) {
        /** A second and a half, and 60 ms a letter: about how long a translated post takes to read. */
        fun hold(least: Long) = (1_500L + 60L * (translated ?: original).length).coerceIn(least, MAX_HOLD_MS)

        /** The translation, when there is one that says it in other words than the original. */
        val translation get() = translated?.takeIf { !same && !alike(it, original) }
    }

    private val finals = LinkedHashMap<Long, Cue>()
    private var live: Cue? = null

    fun apply(event: JSONObject, now: Long = SystemClock.uptimeMillis()) {
        when (event.optString("t")) {
            "final" -> {
                val id = event.optLong("id")
                // Moved to the end: a sentence that grew is the newest.
                finals.remove(id)
                finals[id] = cue(event, now)
                while (finals.size > cues) finals.remove(finals.keys.first())
            }
            "live" -> live = cue(event, now).takeIf { it.original.isNotBlank() }
            "clear" -> {
                finals.clear()
                live = null
            }
        }
    }

    private fun cue(e: JSONObject, now: Long) = Cue(
        original = e.optString("o"),
        translated = if (e.isNull("tr")) null else e.optString("tr").ifBlank { null },
        same = e.optBoolean("same"),
        at = now,
    )

    /**
     * The text to draw: each translation large with its original small under it ([showOriginal]), or the
     * original large when there is no translation (yet) or it already is in the target language. Empty =
     * nothing to show.
     */
    fun text(bigPx: Int, smallPx: Int, showOriginal: Boolean, now: Long = SystemClock.uptimeMillis()): CharSequence {
        finals.values.removeAll { now - it.at > it.hold(holdMs) }
        val out = SpannableStringBuilder()
        for (c in finals.values + listOfNotNull(live)) {
            val translation = c.translation
            if (translation != null) {
                line(out, translation, bigPx, Color.WHITE, bold = true)
                if (showOriginal) line(out, c.original, smallPx, ORIGINAL, bold = false)
            } else {
                line(out, c.original, bigPx, Color.WHITE, bold = true)
            }
        }
        return out
    }

    private fun line(out: SpannableStringBuilder, text: String, px: Int, color: Int, bold: Boolean) {
        if (out.isNotEmpty()) out.append('\n')
        val start = out.length
        out.append(text)
        out.setSpan(AbsoluteSizeSpan(px), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    companion object {
        /** The desktop's colour for originals (`CaptionStyle::orig_color`). */
        private val ORIGINAL = Color.parseColor("#ffe066")
        private const val MAX_HOLD_MS = 12_000L

        /**
         * Whether a translation only repeats its original: text that was in the target language already (read
         * off the screen without some letters, which the translation puts back), or a name.
         */
        fun alike(a: String, b: String): Boolean {
            val (x, y) = key(a) to key(b)
            if (x == y) return true
            val pairs = { s: String -> s.windowed(2).groupingBy { it }.eachCount() }
            val (px, py) = pairs(x) to pairs(y)
            val both = px.entries.sumOf { (k, n) -> minOf(n, py[k] ?: 0) }
            val all = maxOf(1, x.length - 1) + maxOf(1, y.length - 1)
            return 2.0 * both / all >= 0.8
        }

        /** Letters and digits, lower case, without accents: "Çok güzel!" and "Cok guzel" alike. */
        private fun key(s: String): String =
            Normalizer.normalize(s.lowercase().replace('ı', 'i'), Normalizer.Form.NFD)
                .filter { it.isLetterOrDigit() }
    }
}
