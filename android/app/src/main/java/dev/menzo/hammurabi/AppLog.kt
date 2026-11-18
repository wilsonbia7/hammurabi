package dev.menzo.hammurabi

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sets up the log before anything else runs. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}

/**
 * Hammurabi's own log: Android's log, and a file kept between runs (a crash's included), which the main
 * screen copies with "Copy the error log" so a user can send what went wrong.
 */
object AppLog {
    private const val TAG = "hammurabi"
    private const val FILE = "log.txt"

    /** The file is cut to its newer half past this. */
    private const val MAX_BYTES = 200_000L

    /** How much of the file a report takes. */
    private const val REPORT_BYTES = 60_000

    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(this) {
            if (file != null) return
            file = File(context.filesDir, FILE)
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            write("E", "crash in ${thread.name}: ${Log.getStackTraceString(e)}")
            previous?.uncaughtException(thread, e)
        }
        i("app: started, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        write("I", message)
    }

    fun w(message: String, e: Throwable? = null) {
        Log.w(TAG, message, e)
        write("W", if (e == null) message else "$message: $e")
    }

    fun e(message: String, e: Throwable? = null) {
        Log.e(TAG, message, e)
        write("E", if (e == null) message else "$message: ${Log.getStackTraceString(e)}")
    }

    @Synchronized
    private fun write(level: String, message: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            f.appendText("${time.format(Date())} $level $message\n")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "log file", e)
        }
    }

    /** What to send when something went wrong: the phone, the app, its settings and permissions, the log. */
    fun report(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
        val granted = { p: String -> context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        val c = Prefs.load(context)
        val log = synchronized(this) {
            file?.takeIf { it.isFile }?.readText()?.takeLast(REPORT_BYTES) ?: ""
        }
        return buildString {
            appendLine("Hammurabi $version")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("language ${Locale.getDefault().toLanguageTag()}")
            appendLine("settings: $c")
            appendLine(
                "overlay ${Settings.canDrawOverlays(context)}, microphone ${granted(android.Manifest.permission.RECORD_AUDIO)}" +
                    ", running ${CaptionService.running}",
            )
            appendLine("----")
            append(log)
        }
    }
}
