package dev.menzo.hammurabi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** How text read off the screen is put together and chosen; apart, to be tested on its own. */
object ScreenText {
    /** A block's lines as one paragraph. A word broken with a hyphen at a line's end is joined again. */
    fun paragraph(lines: List<String>): String {
        val out = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (out.isEmpty()) {
                out.append(line)
                continue
            }
            val last = out[out.length - 1]
            when {
                // A word broken at the line's end: "transla-" + "tion" loses the hyphen, "well-" + "Known"
                // keeps it; no space either way.
                last == '-' && out.length >= 2 && out[out.length - 2].isLetter() ->
                    if (line[0].isLowerCase()) out.setLength(out.length - 1)
                // Chinese and Japanese put no spaces between words.
                closeUp(last) && closeUp(line[0]) -> {}
                else -> out.append(' ')
            }
            out.append(line)
        }
        return out.toString()
    }

    private fun closeUp(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /** Words rather than a time, a count or a symbol: two letters at least, and a third of the text letters. */
    fun worthTranslating(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        val seen = text.count { !it.isWhitespace() }
        return letters >= 2 && letters * 3 >= seen
    }

    /**
     * Too short for its language to be told on its own (a name, a button): fewer than three words or 16
     * letters. It takes the screen's language.
     */
    fun isShort(text: String): Boolean =
        text.count { it.isLetter() } < 16 || text.trim().split(Regex("\\s+")).size < 3

    /**
     * The language most of the screen's text is in, by letters, for blocks too short to tell on their own;
     * null when that is [target] (nothing to translate) or none is known.
     */
    fun dominant(langs: List<Pair<String, Int>>, target: String): String? {
        val best = langs.filter { it.first != UNDETERMINED }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .maxByOrNull { it.value }?.key
        return best?.takeIf { it != target }
    }

    const val UNDETERMINED = "und"
}

/** One recognizer per script, kept while the app runs: loading one takes a moment. */
object Recognizers {
    private val clients = HashMap<Script, TextRecognizer>()

    @Synchronized
    fun get(script: Script): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    val languages: LanguageIdentifier by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build())
    }

    @Synchronized
    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }
}

/**
 * Translates a picture of the screen, Google Lens's way: reads its text in blocks (a post, a comment, a
 * button), tells each block's language, and translates those not in [target] yet, top to bottom, telling
 * [Listener] (on the main thread) as it goes, so the first translations show while the rest are made.
 */
class ScreenTranslator(
    private val context: Context,
    private val script: Script,
    private val target: String,
) {
    /** A block of text on the screen; [box] in the picture's pixels. */
    class Block(val text: String, val box: Rect, val lineHeight: Int, val lines: Int) {
        /** Its language, once told; [ScreenText.UNDETERMINED] when it could not be. */
        @Volatile
        var lang: String? = null

        @Volatile
        var translation: String? = null
    }

    class Summary(
        val read: Int,
        val translated: Int,
        /** Blocks in the target language already. */
        val same: Int,
        /** Languages read that ML Kit does not translate. */
        val unsupported: Set<String>,
        /** The languages translated from. */
        val from: Set<String>,
        val ms: Long,
    )

    interface Listener {
        fun onRead(blocks: List<Block>)
        fun onDownloading(lang: String)
        fun onTranslated(block: Block)
        fun onDone(summary: Summary)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cancelled = false

    /** No more news after this: the user closed the translation. */
    fun cancel() {
        cancelled = true
    }

    /** Translates [picture], whose rows above [skipTop] (the status bar) are left out. */
    fun run(picture: Bitmap, skipTop: Int, listener: Listener) {
        workers.execute {
            try {
                work(picture, skipTop, listener)
            } catch (e: RuntimeException) {
                AppLog.e("translate: failed", e)
                post { listener.onError(context.getString(R.string.error_translate, e.message ?: e.toString())) }
            }
        }
    }

    private fun post(r: () -> Unit) {
        main.post { if (!cancelled) r() }
    }

    private fun work(picture: Bitmap, skipTop: Int, listener: Listener) {
        val start = SystemClock.uptimeMillis()
        val text = try {
            Tasks.await(Recognizers.get(script).process(InputImage.fromBitmap(picture, 0)), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            AppLog.e("translate: reading failed", e)
            post { listener.onError(context.getString(R.string.error_read)) }
            return
        }
        if (cancelled) return
        val blocks = blocks(text, skipTop)
        AppLog.i("translate: read ${blocks.size} blocks in ${SystemClock.uptimeMillis() - start} ms (${picture.width}x${picture.height})")
        post { listener.onRead(blocks) }
        // Long blocks tell their language; short ones (names, buttons) take the screen's, else a name could
        // pass for French and download a model for nothing.
        for (b in blocks) {
            if (cancelled) return
            b.lang = if (ScreenText.isShort(b.text)) ScreenText.UNDETERMINED else identify(b.text)
        }
        val told = blocks.filter { it.lang != ScreenText.UNDETERMINED }
        val dominant = if (told.isNotEmpty()) {
            ScreenText.dominant(told.map { it.lang!! to it.text.length }, target)
        } else {
            // Only short text (a game's menu): together, it tells its language.
            identify(blocks.joinToString(". ") { it.text }).takeIf { it != ScreenText.UNDETERMINED && it != target }
        }
        for (b in blocks) if (b.lang == ScreenText.UNDETERMINED && dominant != null) b.lang = dominant
        val same = blocks.count { it.lang == target }
        val unsupported = blocks.mapNotNull { it.lang }
            .filter { it != ScreenText.UNDETERMINED && it != target && Languages.supported(it) == null }.toSet()
        val todo = blocks.filter { b -> b.lang.let { it != null && it != target && Languages.supported(it) != null } }
        AppLog.i(
            "translate: languages ${blocks.groupingBy { it.lang }.eachCount()}, " +
                "${todo.size} to translate into $target, ${same} already in it",
        )
        var translated = 0
        for ((lang, group) in todo.groupBy { Languages.supported(it.lang!!)!! }) {
            if (cancelled) return
            if (!Translations.isDownloaded(lang) || !Translations.isDownloaded(target)) {
                val missing = if (Translations.isDownloaded(lang)) target else lang
                AppLog.i("translate: downloading the $missing model")
                post { listener.onDownloading(missing) }
            }
            try {
                Translations.ensure(lang, target)
            } catch (e: Exception) {
                AppLog.e("translate: $lang → $target model download failed", e)
                post { listener.onError(context.getString(R.string.error_model, Languages.name(lang))) }
                return
            }
            for (b in group) {
                if (cancelled) return
                try {
                    val t = Translations.translate(b.text, lang, target)
                    // The same words back (a name, a brand): nothing to cover.
                    if (Captions.alike(t, b.text)) continue
                    b.translation = t
                    translated++
                    post { listener.onTranslated(b) }
                } catch (e: Exception) {
                    AppLog.w("translate: a block failed", e)
                }
            }
        }
        val ms = SystemClock.uptimeMillis() - start
        AppLog.i("translate: done, $translated of ${blocks.size} blocks translated in $ms ms")
        val from = todo.mapNotNull { it.lang }.toSet()
        post { listener.onDone(Summary(blocks.size, translated, same, unsupported, from, ms)) }
    }

    private fun blocks(text: Text, skipTop: Int): List<Block> = text.textBlocks.mapNotNull { b ->
        val box = b.boundingBox ?: return@mapNotNull null
        if (box.bottom <= skipTop || box.width() <= 0 || box.height() <= 0) return@mapNotNull null
        val para = ScreenText.paragraph(b.lines.map { it.text })
        if (!ScreenText.worthTranslating(para)) return@mapNotNull null
        val heights = b.lines.mapNotNull { it.boundingBox?.height() }.sorted()
        val lineHeight = if (heights.isEmpty()) box.height() else heights[heights.size / 2]
        Block(para, Rect(box), lineHeight, maxOf(1, b.lines.size))
    }.sortedWith(compareBy({ it.box.top }, { it.box.left }))

    private fun identify(text: String): String = try {
        val tag = Tasks.await(Recognizers.languages.identifyLanguage(text), 10, TimeUnit.SECONDS)
        if (tag.endsWith("-Latn", ignoreCase = true)) ScreenText.UNDETERMINED else tag
    } catch (e: Exception) {
        AppLog.w("translate: language not told", e)
        ScreenText.UNDETERMINED
    }

    private companion object {
        /** Several at a time: a new translation does not wait for one closed during a download. */
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "hm-translate") }
    }
}
