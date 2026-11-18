package dev.menzo.hammurabi

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** What to listen to. */
enum class Source(val engineName: String) {
    /** People talking near the phone. */
    MIC("Microphone"),

    /** What other apps play: videos, music, games. Calls, and apps that forbid it, stay unheard. */
    SYSTEM("System"),
    BOTH("Both"),
}

/** The choices on the main screen, kept between launches. */
data class Choices(
    val source: Source = Source.MIC,
    /** Null = recognise the language. */
    val spoken: String? = null,
    val translate: Boolean = true,
    val target: String = Locale.getDefault().language.ifEmpty { "en" },
    /** `ModelChoice` name. Tiny keeps up on most phones; Base hears better on fast ones. */
    val model: String = "Tiny",
    /** Captions over other apps too, not only on this screen. */
    val overlay: Boolean = true,
    val showOriginal: Boolean = true,
    /** Processor cores for the speech model; 0 = the fast ones. Takes effect when the app starts again. */
    val threads: Int = 0,
) {
    /** The engine's settings (`Settings` in src/settings.rs); fields left out keep its defaults. */
    fun settingsJson(): String = JSONObject()
        .put("source", source.engineName)
        .put("spoken_language", spoken ?: JSONObject.NULL)
        .put("translate", translate)
        .put("target_language", target)
        .put("model", model)
        // Whisper's 30 s window cut to the speech: much faster on a processor.
        .put("trim_audio", true)
        .toString()
}

object Prefs {
    private const val FILE = "choices"

    fun load(context: Context): Choices {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Choices()
        return Choices(
            source = runCatching { Source.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            spoken = p.getString("spoken", null),
            translate = p.getBoolean("translate", d.translate),
            target = p.getString("target", d.target) ?: d.target,
            model = p.getString("model", d.model) ?: d.model,
            overlay = p.getBoolean("overlay", d.overlay),
            showOriginal = p.getBoolean("show_original", d.showOriginal),
            threads = p.getInt("threads", d.threads),
        )
    }

    fun save(context: Context, c: Choices) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("source", c.source.name)
            .putString("spoken", c.spoken)
            .putBoolean("translate", c.translate)
            .putString("target", c.target)
            .putString("model", c.model)
            .putBoolean("overlay", c.overlay)
            .putBoolean("show_original", c.showOriginal)
            .putInt("threads", c.threads)
            .apply()
    }
}
