package dev.menzo.hammurabi

import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONObject

/** Engine events, handed out on the main thread to whoever shows them. */
object CaptionBus {
    private val listeners = mutableListOf<(JSONObject) -> Unit>()

    /** The last state, status and speed, for a screen that opens while captions run. */
    val last = mutableMapOf<String, JSONObject>()

    fun add(listener: (JSONObject) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (JSONObject) -> Unit) {
        listeners -= listener
    }

    fun emit(event: JSONObject) {
        val t = event.optString("t")
        if (t == "state" || t == "status" || t == "stats" || t == "progress") last[t] = event
        listeners.toList().forEach { it(event) }
    }
}

/**
 * The captions on screen: the last few sentences and the one still being heard, kept as the desktop
 * overlay keeps them (2 cues, each up to 4 s after it last changed).
 */
class Captions(private val cues: Int = 2, private val holdMs: Long = 4_000) {
    private class Cue(val original: String, val translated: String?, val same: Boolean, val at: Long)

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
     * The text to draw: each translation large with its original small under it, or the original large
     * when there is no translation (yet) or it already is in the target language. Empty = nothing to show.
     */
    fun text(bigPx: Int, smallPx: Int, showOriginal: Boolean, now: Long = SystemClock.uptimeMillis()): CharSequence {
        finals.values.removeAll { now - it.at > holdMs }
        val out = SpannableStringBuilder()
        for (c in finals.values + listOfNotNull(live)) {
            if (c.translated != null && !c.same) {
                line(out, c.translated, bigPx, Color.WHITE, bold = true)
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

    private companion object {
        /** The desktop's colour for originals (`CaptionStyle::orig_color`). */
        val ORIGINAL = Color.parseColor("#ffe066")
    }
}
