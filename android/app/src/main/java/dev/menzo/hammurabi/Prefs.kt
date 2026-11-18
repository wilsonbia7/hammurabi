package dev.menzo.hammurabi

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** What automatic subtitles listen to. */
enum class Listen(val engineName: String) {
    /** What other apps play: videos, games. Calls, and apps that forbid it, stay unheard. */
    SYSTEM("System"),

    /** People talking near the phone. */
    MIC("Microphone"),
}

/** Which letters are read off the screen. Each recognizer other than Latin's reads Latin letters too. */
enum class Script { LATIN, CHINESE, JAPANESE, KOREAN }

/** The settings, kept between launches. */
data class Choices(
    /** The language everything is translated into: one of ML Kit's (`Languages`). */
    val target: String = Languages.defaultTarget(),
    /** Automatic subtitles: speech heard, translated at the bottom of the screen. */
    val subtitles: Boolean = false,
    val listen: Listen = Listen.SYSTEM,
    /** The language spoken; null = recognised. */
    val spoken: String? = null,
    /** `ModelChoice` name. Tiny keeps up on most phones; Base hears better on fast ones. */
    val model: String = "Tiny",
    /** The subtitles' size: 0 small, 1 medium, 2 large. */
    val textSize: Int = 1,
    /** Subtitles show what was said under its translation. */
    val showOriginal: Boolean = false,
    val script: Script = Script.LATIN,
    /** Processor cores for the speech model; 0 = the fast ones. Takes effect when the app starts again. */
    val threads: Int = 0,
) {
    /**
     * The engine's settings (`Settings` in src/settings.rs) for automatic subtitles; fields left out keep its
     * defaults. The engine only listens: the app translates what it hears, with ML Kit.
     */
    fun settingsJson(): String = JSONObject()
        .put("asr_backend", "Whisper")
        .put("source", listen.engineName)
        .put("spoken_language", spoken ?: JSONObject.NULL)
        .put("translate", false)
        .put("target_language", target)
        .put("model", model)
        // Whisper's 30 s window cut to the speech: much faster on a processor.
        .put("trim_audio", true)
        // The engine's own messages (an error, a download) in the phone's language, where it has them.
        .put("ui_language", engineLanguage())
        .toString()

    private fun engineLanguage() = when (Locale.getDefault().language) {
        "tr" -> "tr"
        "es" -> "es"
        "pt" -> "pt-BR"
        "zh" -> "zh-Hans"
        else -> "en"
    }
}

object Prefs {
    /** Not "choices", the first version's file: its settings meant other things. */
    private const val FILE = "settings"
    private const val BUBBLE = "bubble"

    fun load(context: Context): Choices {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Choices()
        return Choices(
            target = p.getString("target", null)?.let { Languages.supported(it) } ?: d.target,
            subtitles = p.getBoolean("subtitles", d.subtitles),
            listen = runCatching { Listen.valueOf(p.getString("listen", d.listen.name)!!) }.getOrDefault(d.listen),
            spoken = p.getString("spoken", null),
            model = p.getString("model", d.model) ?: d.model,
            textSize = p.getInt("text_size", d.textSize).coerceIn(0, 2),
            showOriginal = p.getBoolean("show_original", d.showOriginal),
            script = runCatching { Script.valueOf(p.getString("script", d.script.name)!!) }.getOrDefault(d.script),
            threads = p.getInt("threads", d.threads),
        )
    }

    fun save(context: Context, c: Choices) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("target", c.target)
            .putBoolean("subtitles", c.subtitles)
            .putString("listen", c.listen.name)
            .putString("spoken", c.spoken)
            .putString("model", c.model)
            .putInt("text_size", c.textSize)
            .putBoolean("show_original", c.showOriginal)
            .putString("script", c.script.name)
            .putInt("threads", c.threads)
            .apply()
    }

    /** Changes the saved settings with [change] and returns them. */
    fun update(context: Context, change: (Choices) -> Choices): Choices {
        val c = change(load(context))
        save(context, c)
        return c
    }

    /** Where the bubble sits: on the right edge or the left, and how far down the screen (0 to 1). */
    fun bubble(context: Context): Pair<Boolean, Float> {
        val p = context.getSharedPreferences(BUBBLE, Context.MODE_PRIVATE)
        return p.getBoolean("right", true) to p.getFloat("y", 0.35f).coerceIn(0f, 1f)
    }

    fun saveBubble(context: Context, right: Boolean, y: Float) {
        context.getSharedPreferences(BUBBLE, Context.MODE_PRIVATE).edit()
            .putBoolean("right", right).putFloat("y", y).apply()
    }

    /** Whether the bubble's "tap / hold" hint was shown; true once it is. */
    fun hintShown(context: Context): Boolean =
        context.getSharedPreferences(BUBBLE, Context.MODE_PRIVATE).getBoolean("hint", false)

    fun setHintShown(context: Context) {
        context.getSharedPreferences(BUBBLE, Context.MODE_PRIVATE).edit().putBoolean("hint", true).apply()
    }
}
