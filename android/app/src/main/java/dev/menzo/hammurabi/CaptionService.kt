package dev.menzo.hammurabi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Runs while Hammurabi is on: the bubble over every app, the screen sharing its pictures come from, and
 * automatic subtitles when they are on. A foreground service, so it keeps going while another app (a feed, a
 * video, a game) is in front.
 */
class CaptionService : Service(), Bubble.Events, BubbleMenu.Actions {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capture: ScreenCapture? = null
    private var bubble: Bubble? = null
    private var menu: BubbleMenu? = null

    /** The screen translated, while it shows, and the picture it was made from. */
    private var layer: TranslationLayer? = null
    private var shot: Bitmap? = null
    private var translator: ScreenTranslator? = null

    /** Automatic subtitles, while on. */
    private var subtitles = false
    private val recorders = mutableListOf<Recorder>()
    private var overlay: CaptionOverlay? = null
    private var subtitleTranslator: SubtitleTranslator? = null

    /** The foreground service may use the microphone: started so, with the permission given. */
    private var micAllowed = false

    /** Bumped at every subtitles start and stop: a poller from an earlier start sees it changed and ends. */
    @Volatile
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_SHARE -> if (running) share(intent) else begin(intent)
            ACTION_STOP -> end()
            // Restarted by the system without our intent: no screen sharing to go on with.
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The screen turned: the translation's places no longer match, the bubble goes back to its edge.
        layer?.close()
        menu?.dismiss()
        capture?.resize()
        bubble?.relayout()
    }

    override fun onDestroy() {
        end()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        if (running) {
            share(intent)
            return
        }
        CaptionBus.last.clear()
        if (!foreground(granted(Manifest.permission.RECORD_AUDIO))) {
            fail(getString(R.string.error_foreground))
            return
        }
        running = true
        AppLog.i("service: started")
        CaptionBus.emit(JSONObject().put("t", "service").put("on", true))
        if (!project(intent)) {
            fail(getString(R.string.error_projection))
            return
        }
        bubble = Bubble(this, this).also { it.show() }
        menu = BubbleMenu(this, this)
        val c = Prefs.load(this)
        prepare(c)
        if (c.subtitles) startSubtitles()
    }

    /** Screen sharing again, after it ended (the screen locked, or stopped from the status bar). */
    private fun share(intent: Intent) {
        foreground(micAllowed)
        if (!project(intent)) {
            toast(getString(R.string.error_projection))
            return
        }
        bubble?.needsSharing = false
        // Subtitles of the phone's sound stopped with the sharing.
        if (Prefs.load(this).subtitles && !subtitles) startSubtitles()
    }

    /** The foreground service, with the microphone when [mic]; false when Android does not allow it now. */
    private fun foreground(mic: Boolean): Boolean {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // The microphone type came in Android 11; Android 10 refuses a type it does not know.
        val withMic = mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            startForeground(NOTIFICATION_ID, notification(), type)
            micAllowed = mic
            true
        } catch (e: RuntimeException) {
            AppLog.e("service: foreground refused (microphone $withMic)", e)
            false
        }
    }

    /** Takes the screen sharing the user agreed to (Android 14: once the foreground service runs with it). */
    private fun project(intent: Intent): Boolean {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = intent.parcelableExtra(EXTRA_PROJECTION_DATA) ?: return false
        val code = intent.getIntExtra(EXTRA_PROJECTION_CODE, 0)
        val p = try {
            manager.getMediaProjection(code, data)
        } catch (e: RuntimeException) {
            AppLog.e("service: no screen sharing", e)
            null
        } ?: return false
        dropProjection()
        // Stopped from the status bar or by the screen lock. Only this sharing's end: one replaced by a new
        // one must not end that.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { if (projection === p) projectionEnded() }
            }
        }
        p.registerCallback(callback, main)
        projection = p
        projectionCallback = callback
        capture = ScreenCapture(this, p).also { it.start() }
        AppLog.i("service: screen sharing on")
        return true
    }

    private fun projectionEnded() {
        AppLog.i("service: screen sharing ended")
        layer?.close()
        dropProjection()
        bubble?.needsSharing = true
        if (subtitles && Prefs.load(this).listen == Listen.SYSTEM) stopSubtitles()
    }

    private fun dropProjection() {
        capture?.stop()
        capture = null
        val p = projection
        projection = null
        projectionCallback?.let { c -> p?.let { runCatching { it.unregisterCallback(c) } } }
        projectionCallback = null
        runCatching { p?.stop() }
    }

    /** The first translation is quicker with the reader loaded and the target language downloaded. */
    private fun prepare(c: Choices) {
        warmup.execute {
            runCatching { Recognizers.get(c.script) }
            for (lang in listOf(c.target, "en").distinct()) {
                if (Translations.isDownloaded(lang)) continue
                CaptionBus.post(JSONObject().put("t", "model").put("lang", lang).put("state", "downloading"))
                val ok = try {
                    Translations.download(lang)
                    true
                } catch (e: Exception) {
                    AppLog.w("service: $lang model not downloaded ahead", e)
                    false
                }
                CaptionBus.post(JSONObject().put("t", "model").put("lang", lang).put("state", if (ok) "ready" else "failed"))
            }
        }
    }

    // The bubble.

    override fun onPress() {
        menu?.dismiss()
        // Mirroring starts now, before the bubble goes: the picture after shows the screen without it.
        capture?.wake()
    }

    override fun onTap() {
        translateScreen()
    }

    override fun onHold() {
        val b = bubble ?: return
        menu?.show(b.bounds(), b.onRight, Prefs.load(this), subtitles)
    }

    // The bubble's menu.

    override fun translateScreen() {
        if (layer != null) return
        val cap = capture
        if (cap == null) {
            askSharing()
            return
        }
        cap.wake()
        bubble?.setHidden(true)
        val c = Prefs.load(this)
        val l = TranslationLayer(
            this, c.target,
            onClosed = { layerClosed() },
            onLanguage = { code -> retranslate(code) },
        )
        layer = l
        AppLog.i("translate: started (${c.script}, into ${c.target})")
        l.show {
            cap.grab { bitmap ->
                if (layer !== l) {
                    bitmap?.recycle()
                    return@grab
                }
                if (bitmap == null) {
                    l.onError(getString(R.string.error_capture))
                    return@grab
                }
                shot = bitmap
                l.picture(bitmap, screenSize(this).first)
                translate(bitmap, c.target, c.script)
            }
        }
    }

    private fun translate(bitmap: Bitmap, target: String, script: Script) {
        translator?.cancel()
        val l = layer ?: return
        translator = ScreenTranslator(this, script, target).also { it.run(bitmap, statusBarHeight(), l) }
    }

    /** The same picture into another language, chosen on the translation. */
    private fun retranslate(code: String) {
        val c = Prefs.update(this) { it.copy(target = code) }
        subtitleTranslator?.target = code
        val bitmap = shot ?: return
        layer?.reading()
        translate(bitmap, code, c.script)
    }

    private fun layerClosed() {
        AppLog.i("translate: closed")
        translator?.cancel()
        translator = null
        layer = null
        // The layer let the picture go.
        shot = null
        bubble?.setHidden(false)
    }

    override fun setSubtitles(on: Boolean) {
        Prefs.update(this) { it.copy(subtitles = on) }
        if (on) startSubtitles() else stopSubtitles()
    }

    override fun setTarget(code: String) {
        val c = Prefs.update(this) { it.copy(target = code) }
        subtitleTranslator?.target = code
        prepare(c)
    }

    override fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun quit() {
        end()
    }

    /** Asks for screen sharing again, over the app in front. */
    private fun askSharing() {
        startActivity(
            Intent(this, StartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(StartActivity.EXTRA_MODE, StartActivity.MODE_SHARE),
        )
    }

    /** Asks for the microphone permission, which the phone's sound needs too, over the app in front. */
    private fun askMicrophone() {
        startActivity(
            Intent(this, StartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(StartActivity.EXTRA_MODE, StartActivity.MODE_MICROPHONE),
        )
    }

    /** Called by [StartActivity] while it is in front, the permission just given. */
    fun microphoneGranted() {
        if (!running) return
        if (!micAllowed && !foreground(true)) {
            toast(getString(R.string.error_microphone_service))
            return
        }
        startSubtitles()
    }

    /** The settings changed on the main screen while this runs. */
    fun settingsChanged(before: Choices, now: Choices) {
        if (!running) return
        subtitleTranslator?.target = now.target
        overlay?.update(now)
        val restart = before.listen != now.listen || before.model != now.model || before.spoken != now.spoken
        when {
            now.subtitles && (!subtitles || restart) -> {
                stopSubtitles()
                startSubtitles()
            }
            !now.subtitles && subtitles -> stopSubtitles()
        }
        if (before.target != now.target || before.script != now.script) prepare(now)
    }

    // Automatic subtitles.

    private fun startSubtitles() {
        if (subtitles || !running) return
        val c = Prefs.load(this)
        if (!granted(Manifest.permission.RECORD_AUDIO) || !micAllowed) {
            askMicrophone()
            return
        }
        val p = projection
        if (c.listen == Listen.SYSTEM && p == null) {
            toast(getString(R.string.need_sharing_for_sound))
            askSharing()
            return
        }
        subtitles = true
        AppLog.i("subtitles: starting (${c.listen}, ${c.model})")
        overlay = CaptionOverlay(this, c).also { it.show() }
        subtitleTranslator = SubtitleTranslator(this, c.target)
        CaptionBus.emit(JSONObject().put("t", "state").put("state", "loading"))
        Engine.run {
            val error = Engine.setup(this) ?: if (Native.start(c.settingsJson())) null else getString(R.string.error_settings)
            main.post {
                // Stopped meanwhile: the stop queued after this start ends the engine.
                if (!subtitles) return@post
                if (error != null) {
                    subtitlesFailed(error)
                    return@post
                }
                startRecording(c.listen, p)
            }
        }
    }

    private fun startRecording(listen: Listen, p: MediaProjection?) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            recorders += if (listen == Listen.SYSTEM && p != null) {
                Recorder.system(p, Silence(audio) { main.post { onSilentApp() } })
            } else {
                Recorder.microphone()
            }
        } catch (e: RuntimeException) {
            subtitlesFailed(getString(R.string.error_recorder, e.message ?: e.toString()))
            return
        }
        recorders.forEach { it.start() }
        val gen = ++generation
        thread(name = "hm-poll") { poll(gen) }
    }

    private fun poll(gen: Int) {
        while (gen == generation) {
            val json = Native.poll(250) ?: continue
            val event = try {
                JSONObject(json)
            } catch (e: JSONException) {
                continue
            }
            main.post { if (gen == generation) onEngineEvent(event) }
        }
    }

    private fun onEngineEvent(event: JSONObject) {
        if (!subtitles) return
        when (event.optString("t")) {
            "ended" -> subtitlesFailed(getString(R.string.error_engine))
            "final", "live", "clear" -> subtitleTranslator?.handle(event) { CaptionBus.emit(it) }
            else -> CaptionBus.emit(event)
        }
    }

    /** Something plays, yet what is heard of it is silence: the app playing does not let its sound be captured. */
    private fun onSilentApp() {
        if (!subtitles) return
        CaptionBus.emit(
            JSONObject().put("t", "status").put("level", "warning")
                .put("text", getString(R.string.silent_app)).put("ttl_ms", SILENT_NOTICE_MS),
        )
    }

    private fun stopSubtitles() {
        if (!subtitles) return
        subtitles = false
        AppLog.i("subtitles: stopped")
        // The poller ends within one poll.
        generation++
        recorders.forEach { it.stop() }
        recorders.clear()
        overlay?.hide()
        overlay = null
        subtitleTranslator?.close()
        subtitleTranslator = null
        Engine.run { Native.stop() }
        CaptionBus.emit(JSONObject().put("t", "state").put("state", "idle"))
    }

    private fun subtitlesFailed(message: String) {
        AppLog.e("subtitles: $message")
        CaptionBus.emit(JSONObject().put("t", "status").put("level", "error").put("text", message))
        toast(getString(R.string.subtitles_stopped_with, message))
        stopSubtitles()
    }

    // Ending.

    private fun end() {
        if (running) {
            running = false
            AppLog.i("service: stopped")
            layer?.close()
            menu?.dismiss()
            menu = null
            bubble?.remove()
            bubble = null
            stopSubtitles()
            dropProjection()
            warmup.execute {
                Translations.close()
                Recognizers.close()
            }
            CaptionBus.emit(JSONObject().put("t", "service").put("on", false))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        AppLog.e("service: $message")
        CaptionBus.emit(JSONObject().put("t", "status").put("level", "error").put("text", message))
        toast(getString(R.string.stopped_with, message))
        failureNotice(message)
        end()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The status bar's height, whose text (the time, the signal) is not translated. */
    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        }
        return Ui.dp(this, 24f)
    }

    /** Stays in the notifications after the service is gone; tapping it opens Hammurabi. */
    private fun failureNotice(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            FAILURE_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.stopped))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), getString(R.string.stop), stop,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_START = "dev.menzo.hammurabi.START"
        const val ACTION_SHARE = "dev.menzo.hammurabi.SHARE"
        const val ACTION_STOP = "dev.menzo.hammurabi.STOP"
        const val EXTRA_PROJECTION_CODE = "projection_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL = "captions"
        private const val NOTIFICATION_ID = 1
        private const val FAILURE_ID = 2
        private const val SILENT_NOTICE_MS = 10_000L

        /** Off the main thread: models loaded and downloaded ahead, freed at the end. */
        private val warmup = Executors.newSingleThreadExecutor { r -> Thread(r, "hm-warmup") }

        /** Hammurabi is on. Main thread only. */
        var running = false
            private set

        /** The service while it exists, for the screens of the same app. Main thread only. */
        var instance: CaptionService? = null
            private set
    }
}

/**
 * Translates automatic subtitles with ML Kit as they come: the engine only listens. A sentence shows as
 * heard at once and is replaced by its translation; a sentence still being heard is translated as it grows,
 * an older translation of it dropped when a newer one is on its way.
 */
class SubtitleTranslator(private val context: Context, @Volatile var target: String) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hm-subtitles") }
    private val main = Handler(Looper.getMainLooper())
    private var liveSeq = 0
    private val ready = HashSet<String>()

    @Volatile
    private var closed = false

    /** [event] as it is, then with its translation, to [emit] on the main thread. */
    fun handle(event: JSONObject, emit: (JSONObject) -> Unit) {
        val kind = event.optString("t")
        val text = event.optString("o")
        emit(event)
        if (kind == "clear" || text.isBlank() || (!event.isNull("tr") && event.optString("tr").isNotBlank())) return
        val seq = if (kind == "live") ++liveSeq else 0
        worker.execute {
            // A newer version of the sentence being heard is on its way: this one would only flash.
            if (closed || (kind == "live" && seq != liveSeq)) return@execute
            val out = translated(event, text, emit) ?: return@execute
            main.post { if (!closed && (kind != "live" || seq == liveSeq)) emit(out) }
        }
    }

    private fun translated(event: JSONObject, text: String, emit: (JSONObject) -> Unit): JSONObject? {
        val to = target
        val lang = Languages.supported(event.optString("lang")) ?: identify(text) ?: return null
        val out = JSONObject(event.toString())
        if (lang == to) return out.put("same", true)
        if (lang !in ready || to !in ready) {
            if (!Translations.isDownloaded(lang) || !Translations.isDownloaded(to)) {
                val line = JSONObject().put("t", "status").put("level", "info").put("ttl_ms", 20_000L)
                    .put("text", context.getString(R.string.layer_downloading, Languages.name(lang)))
                main.post { if (!closed) emit(line) }
            }
            try {
                Translations.ensure(lang, to)
            } catch (e: Exception) {
                AppLog.w("subtitles: $lang → $to model not downloaded", e)
                return null
            }
            ready += lang
            ready += to
        }
        return try {
            out.put("tr", Translations.translate(text, lang, to)).put("same", false)
        } catch (e: Exception) {
            AppLog.w("subtitles: not translated", e)
            null
        }
    }

    private fun identify(text: String): String? = try {
        val tag = com.google.android.gms.tasks.Tasks.await(
            Recognizers.languages.identifyLanguage(text), 5, java.util.concurrent.TimeUnit.SECONDS,
        )
        Languages.supported(tag)
    } catch (e: Exception) {
        null
    }

    fun close() {
        closed = true
        worker.shutdown()
    }
}

private fun Intent.parcelableExtra(name: String): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

/** Told once when other apps' sound stays silent while one of them plays: it does not let its sound out. */
private class Silence(val audio: AudioManager, val tell: () -> Unit)

/** One AudioRecord, read on its own thread 100 ms at a time into the engine. */
private class Recorder(
    private val record: AudioRecord,
    private val rate: Int,
    private val system: Boolean,
    private val silence: Silence? = null,
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        running = true
        worker = thread(name = if (system) "hm-rec-system" else "hm-rec-mic") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(rate / 10)
            var silentMs = 0L
            var told = false
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        AppLog.w("subtitles: recording stopped ($n)")
                        break
                    }
                    if (n > 0) Native.feed(buf, n, rate, system)
                    if (silence != null && !told && n > 0) {
                        // An app that forbids capture is left out of what is recorded: digital silence.
                        silentMs = if ((0 until n).all { kotlin.math.abs(buf[it].toInt()) <= 2 }) {
                            silentMs + n * 1000L / rate
                        } else {
                            0L
                        }
                        if (silentMs >= SILENT_MS && silence.audio.isMusicActive) {
                            told = true
                            silence.tell()
                        }
                    }
                }
            } catch (e: RuntimeException) {
                AppLog.w("subtitles: recording failed", e)
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
    }

    companion object {
        /** The engine hears 16 kHz; other rates are resampled there. */
        private val RATES = intArrayOf(16_000, 48_000, 44_100)

        /** Silence while something plays this long means its sound is not let out. */
        private const val SILENT_MS = 6_000L

        // Both are asked for before captions start.
        @SuppressLint("MissingPermission")
        fun microphone(): Recorder = open(system = false) { rate ->
            AudioRecord.Builder()
                // Tuned for speech recognition: no effects that smear words.
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format(rate))
                .setBufferSizeInBytes(bufferBytes(rate))
                .build()
        }

        @SuppressLint("MissingPermission")
        fun system(projection: MediaProjection, silence: Silence): Recorder {
            // What apps play for people to listen to; calls and alarms stay out.
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            return open(system = true, silence) { rate ->
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format(rate))
                    .setBufferSizeInBytes(bufferBytes(rate))
                    .build()
            }
        }

        private fun open(system: Boolean, silence: Silence? = null, build: (Int) -> AudioRecord): Recorder {
            var last: RuntimeException? = null
            for (rate in RATES) {
                val record = try {
                    build(rate)
                } catch (e: RuntimeException) {
                    last = e
                    continue
                }
                if (record.state == AudioRecord.STATE_INITIALIZED) return Recorder(record, rate, system, silence)
                record.release()
            }
            throw IllegalStateException("no recording format works here", last)
        }

        private fun format(rate: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        /** Half a second of room, so a busy moment loses no audio. */
        private fun bufferBytes(rate: Int) =
            maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate)
    }
}
