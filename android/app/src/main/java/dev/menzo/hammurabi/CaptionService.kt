package dev.menzo.hammurabi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
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
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import kotlin.concurrent.thread

private const val TAG = "hammurabi"

/**
 * Runs while captions are on: records the microphone and/or what other apps play, or captures the screen,
 * feeds it to the engine, and hands the engine's events to the screens. A foreground service, so it keeps
 * going while another app (a video, a feed, a game) is in front.
 */
class CaptionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private val recorders = mutableListOf<Recorder>()
    private var capture: ScreenCapture? = null
    private var overlay: CaptionOverlay? = null

    /** Bumped at every start and stop: a poller from an earlier start sees it changed and ends. */
    @Volatile
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> end()
            // Restarted by the system without our intent: nothing to listen to.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The screen turned: the pictures read turn with it.
        capture?.resize()
    }

    override fun onDestroy() {
        end()
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        if (running) stopListening()
        // What an earlier run said (its error, say) is not about this one.
        CaptionBus.last.clear()
        val source = runCatching { Source.valueOf(intent.getStringExtra(EXTRA_SOURCE)!!) }.getOrDefault(Source.MIC)
        val settings = intent.getStringExtra(EXTRA_SETTINGS) ?: "{}"
        var type = 0
        if (source == Source.MIC || source == Source.BOTH) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (source != Source.MIC) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            startForeground(NOTIFICATION_ID, notification(), type)
        } catch (e: RuntimeException) {
            // Not allowed now: a permission is missing, or the app is not in front.
            fail(e.message ?: e.toString())
            return
        }
        running = true
        emitState("loading")
        if (source != Source.MIC) {
            // Android 14: only once the foreground service runs with the projection type.
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val data = intent.parcelableExtra(EXTRA_PROJECTION_DATA)
            val code = intent.getIntExtra(EXTRA_PROJECTION_CODE, 0)
            val p = data?.let { runCatching { manager.getMediaProjection(code, it) }.getOrNull() }
            if (p == null) {
                fail(getString(R.string.error_projection))
                return
            }
            // Stopped from the status bar or the screen lock: captions end too.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { end() }
                }

                // Android 14: the app shared alone took another size.
                override fun onCapturedContentResize(width: Int, height: Int) {
                    capture?.resize(width, height)
                }
            }, main)
            projection = p
        }
        val wantsOverlay = intent.getBooleanExtra(EXTRA_OVERLAY, false)
        val showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true)
        Engine.run {
            val error = Engine.setup(this) ?: when {
                source == Source.SCREEN && !OcrModels.ensure(this) -> getString(R.string.error_ocr_models)
                !Native.start(settings) -> getString(R.string.error_settings)
                else -> null
            }
            main.post {
                // Stopped meanwhile: the stop queued after this start ends the engine.
                if (!running) return@post
                if (error != null) {
                    fail(error)
                    return@post
                }
                startListening(source)
                if (running && wantsOverlay && Settings.canDrawOverlays(this)) {
                    // Text read off the screen is there already: only its translation is shown.
                    overlay = CaptionOverlay(
                        this,
                        showOriginal = showOriginal && source != Source.SCREEN,
                        translatedOnly = source == Source.SCREEN,
                        source = source,
                    ).also { it.show() }
                }
            }
        }
    }

    private fun startListening(source: Source) {
        try {
            val p = projection
            if (source == Source.SCREEN && p != null) {
                capture = ScreenCapture(this, p).also { it.start() }
            } else {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                p?.let { recorders += Recorder.system(it, Silence(audio) { main.post { onSilentApp() } }) }
                if (source == Source.MIC || source == Source.BOTH) recorders += Recorder.microphone()
            }
        } catch (e: RuntimeException) {
            fail(getString(R.string.error_recorder, e.message ?: e.toString()))
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
            main.post { onEvent(event) }
        }
    }

    /** Something plays, yet what is heard of it is silence: the app playing does not let its sound be captured. */
    private fun onSilentApp() {
        if (!running) return
        CaptionBus.emit(
            JSONObject().put("t", "status").put("level", "warning")
                .put("text", getString(R.string.silent_app)).put("ttl_ms", SILENT_NOTICE_MS),
        )
    }

    private fun onEvent(event: JSONObject) {
        if (!running) return
        if (event.optString("t") == "ended") {
            fail(getString(R.string.error_engine))
            return
        }
        CaptionBus.emit(event)
    }

    private fun stopListening() {
        // The poller ends within one poll.
        generation++
        recorders.forEach { it.stop() }
        recorders.clear()
        capture?.stop()
        capture = null
        overlay?.hide()
        overlay = null
        val p = projection
        projection = null
        p?.stop()
        Engine.run { Native.stop() }
    }

    private fun end() {
        if (running) {
            running = false
            stopListening()
            emitState("idle")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        CaptionBus.emit(JSONObject().put("t", "status").put("level", "error").put("text", message))
        // Seen over the app in front, where the captions were to show.
        Toast.makeText(this, getString(R.string.stopped_with, message), Toast.LENGTH_LONG).show()
        failureNotice(message)
        end()
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

    private fun emitState(state: String) {
        CaptionBus.emit(JSONObject().put("t", "state").put("state", state))
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
        const val ACTION_STOP = "dev.menzo.hammurabi.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_SHOW_ORIGINAL = "show_original"
        const val EXTRA_PROJECTION_CODE = "projection_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL = "captions"
        private const val NOTIFICATION_ID = 1
        private const val FAILURE_ID = 2
        private const val SILENT_NOTICE_MS = 10_000L

        /** Captions are on. Main thread only. */
        var running = false
            private set
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
                        Log.w(TAG, "recording stopped ($n)")
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
                Log.w(TAG, "recording failed", e)
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
