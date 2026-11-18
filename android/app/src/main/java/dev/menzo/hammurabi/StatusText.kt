package dev.menzo.hammurabi

import android.content.Context
import org.json.JSONObject

/**
 * The engine's events that say how things go, as a line for people: what is downloading or loading, that
 * captions run, a warning or an error. The main screen and the captions over other apps both show them.
 */
object StatusText {
    class Line(val text: String, val error: Boolean, val ttlMs: Long)

    /** How long "Reading the screen" or "Listening" stays once captions run. */
    private const val RUNNING_MS = 3_000L

    /**
     * How long a download's line stays after its last news (downloads tell every few hundred milliseconds),
     * and a model's loading (which tells nothing until it is done): no stale line when it is over.
     */
    private const val DOWNLOAD_MS = 15_000L
    private const val LOADING_MS = 60_000L

    /** The line for [e], or null when it is not about how things go. [source]: what captions are made of. */
    fun of(context: Context, e: JSONObject, source: Source?): Line? = when (e.optString("t")) {
        "state" -> when (e.optString("state")) {
            "loading" -> Line(context.getString(R.string.state_loading), false, 0L)
            "running" -> Line(
                context.getString(if (source == Source.SCREEN) R.string.state_reading else R.string.state_running),
                false,
                RUNNING_MS,
            )
            else -> null
        }
        "status" -> e.optString("text").takeIf { it.isNotBlank() }?.let {
            Line(it, e.optString("level") == "error", e.optLong("ttl_ms", 0L))
        }
        "progress" -> {
            val name = e.optString("model")
            val done = e.optLong("done")
            val total = e.optLong("total", 0L)
            val loading = e.optString("phase") == "loading"
            Line(
                when {
                    loading -> context.getString(R.string.loading_model, name)
                    total > 0 -> context.getString(R.string.downloading, name, (done * 100 / total).toInt())
                    else -> context.getString(R.string.downloading_size, name, done / 1_000_000)
                },
                false,
                if (loading) LOADING_MS else DOWNLOAD_MS,
            )
        }
        else -> null
    }
}
