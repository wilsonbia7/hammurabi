package dev.menzo.hammurabi

import android.content.Context
import java.util.concurrent.Executors

/** The engine in Rust (android/rust, libhammurabi_android.so). */
object Native {
    init {
        // ONNX Runtime first: the engine opens it by name for its voice detector.
        System.loadLibrary("onnxruntime")
        System.loadLibrary("hammurabi_android")
    }

    /** Once per process, before the rest; later calls change nothing. Null, or why the engine cannot run. */
    @JvmStatic external fun setup(filesDir: String, threads: Int): String?

    /** JSON: the languages, and the speech models with their sizes and whether they are downloaded. */
    @JvmStatic external fun info(): String

    /** Starts an engine with these settings (the desktop app's settings as JSON). False when they do not parse. */
    @JvmStatic external fun start(settingsJson: String): Boolean

    @JvmStatic external fun reconfigure(settingsJson: String)

    /** 16-bit mono audio as recorded; [system] = other apps' sound rather than the microphone. */
    @JvmStatic external fun feed(samples: ShortArray, count: Int, sampleRate: Int, system: Boolean): Boolean

    /** The next event as JSON, or null when none came within [timeoutMs]. */
    @JvmStatic external fun poll(timeoutMs: Int): String?

    /** Ends the engine; waits up to a few seconds for it to free its models. */
    @JvmStatic external fun stop()
}

/** Calls the engine off the main thread, one call at a time, so a stop always ends before the next start. */
object Engine {
    private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "hm-engine-calls") }
    private var setupResult: String? = null
    private var setupDone = false

    /** [Native.setup] with the app's files folder and the chosen thread count. */
    @Synchronized
    fun setup(context: Context): String? {
        if (!setupDone) {
            setupResult = try {
                Native.setup(context.filesDir.absolutePath, Prefs.load(context).threads)
            } catch (e: LinkageError) {
                // The libraries are missing from this build or do not load on this phone.
                e.message ?: e.toString()
            }
            setupDone = true
        }
        return setupResult
    }

    fun run(call: () -> Unit) {
        calls.execute(call)
    }
}
