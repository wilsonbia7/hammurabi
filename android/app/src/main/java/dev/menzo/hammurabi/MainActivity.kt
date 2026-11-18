package dev.menzo.hammurabi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** The main screen: on and off, how to use it, and the settings, in cards. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var stateNote: TextView
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private lateinit var target: Spinner
    private lateinit var modelStatus: TextView
    private lateinit var download: Button
    private lateinit var script: Spinner
    private lateinit var subtitles: Switch
    private lateinit var subtitleOptions: View
    private lateinit var listen: Spinner
    private lateinit var listenNote: TextView
    private lateinit var spoken: Spinner
    private lateinit var model: Spinner
    private lateinit var textSize: Spinner
    private lateinit var showOriginal: Switch
    private lateinit var stats: TextView
    private lateinit var engineNote: TextView

    private val targets = Languages.byName()

    // Until the engine tells its own (`Native.info`).
    private var spokenLangs = listOf("en", "tr")
    private var models = listOf("Tiny", "Base", "Small")
    private var modelInfo = JSONArray()
    private var engineError: String? = null

    private var current: Choices? = null
    private var notificationsAsked = false
    private var overlayAsked = false

    /** Start was tapped and waits for the overlay permission, given in Android's settings. */
    private var startPending = false
    private var downloading: String? = null
    private var clearStatus: Runnable? = null

    private val listener: (JSONObject) -> Unit = { onEvent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val content = requireViewById<View>(R.id.content)
        val pad = content.paddingLeft
        // The screen reaches under the status and navigation bars: the content keeps clear of them.
        content.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(
                pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom,
            )
            insets
        }
        stateDot = requireViewById(R.id.state_dot)
        stateText = requireViewById(R.id.state_text)
        stateNote = requireViewById(R.id.state_note)
        toggle = requireViewById(R.id.toggle)
        statusView = requireViewById(R.id.status)
        target = requireViewById(R.id.target)
        modelStatus = requireViewById(R.id.model_status)
        download = requireViewById(R.id.download)
        script = requireViewById(R.id.script)
        subtitles = requireViewById(R.id.subtitles)
        subtitleOptions = requireViewById(R.id.subtitle_options)
        listen = requireViewById(R.id.listen)
        listenNote = requireViewById(R.id.listen_note)
        spoken = requireViewById(R.id.spoken)
        model = requireViewById(R.id.model)
        textSize = requireViewById(R.id.text_size)
        showOriginal = requireViewById(R.id.show_original)
        stats = requireViewById(R.id.stats)
        engineNote = requireViewById(R.id.engine_note)

        val addTile = requireViewById<Button>(R.id.add_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.visibility = View.VISIBLE
            addTile.setOnClickListener { askToAddTile() }
        }
        requireViewById<Button>(R.id.copy_log).setOnClickListener {
            Ui.copy(this, "Hammurabi", AppLog.report(this))
        }
        requireViewById<TextView>(R.id.version).text = getString(R.string.version, versionName())
        download.setOnClickListener { downloadTarget() }
        toggle.setOnClickListener { onToggle() }

        // The engine is for subtitles only: the screen is translated without it.
        engineError = Engine.setup(this)
        if (engineError == null) readInfo()
        fill(Prefs.load(this))
    }

    override fun onStart() {
        super.onStart()
        CaptionBus.add(listener)
        CaptionBus.last.values.toList().forEach { onEvent(it) }
        updateState()
        refreshModel()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's settings with the overlay permission: Start goes on.
        if (startPending && Settings.canDrawOverlays(this)) {
            startPending = false
            onToggle()
        }
    }

    override fun onStop() {
        CaptionBus.remove(listener)
        super.onStop()
    }

    private fun readInfo() {
        val info = JSONObject(Native.info())
        spokenLangs = info.getJSONArray("source").strings()
        modelInfo = info.getJSONArray("models")
        models = (0 until modelInfo.length()).map { modelInfo.getJSONObject(it).getString("id") }
    }

    private fun fill(c: Choices) {
        target.adapter = adapter(targets.map { Languages.name(it) })
        target.setSelection(targets.indexOf(c.target).coerceAtLeast(0))
        // In the order of `Script`.
        script.adapter = adapter(
            listOf(R.string.script_latin, R.string.script_chinese, R.string.script_japanese, R.string.script_korean)
                .map { getString(it) },
        )
        script.setSelection(c.script.ordinal)
        subtitles.isChecked = c.subtitles
        // In the order of `Listen`.
        listen.adapter = adapter(listOf(getString(R.string.listen_system), getString(R.string.listen_mic)))
        listen.setSelection(c.listen.ordinal)
        spoken.adapter = adapter(listOf(getString(R.string.spoken_auto)) + spokenLangs.map { Languages.name(it) })
        spoken.setSelection(c.spoken?.let { spokenLangs.indexOf(it) + 1 } ?: 0)
        model.adapter = adapter(models.indices.map { modelLabel(it) })
        model.setSelection(models.indexOf(c.model).coerceAtLeast(0))
        textSize.adapter = adapter(listOf(getString(R.string.size_small), getString(R.string.size_medium), getString(R.string.size_large)))
        textSize.setSelection(c.textSize)
        showOriginal.isChecked = c.showOriginal
        current = readChoices()
        updateEnabled()
        // Spinners report their first selection later on their own; `onChoicesChanged` ignores
        // anything that changes nothing.
        val changed = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onChoicesChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listOf(target, script, listen, spoken, model, textSize).forEach { it.onItemSelectedListener = changed }
        showOriginal.setOnCheckedChangeListener { _, _ -> onChoicesChanged() }
        subtitles.setOnCheckedChangeListener { _, on ->
            updateEnabled()
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // Saved once the microphone is allowed.
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MICROPHONE)
            } else {
                onChoicesChanged()
            }
        }
    }

    private fun readChoices(): Choices {
        val saved = Prefs.load(this)
        return saved.copy(
            target = targets.getOrElse(target.selectedItemPosition) { saved.target },
            script = Script.entries[script.selectedItemPosition.coerceIn(0, Script.entries.size - 1)],
            subtitles = subtitles.isChecked,
            listen = Listen.entries[listen.selectedItemPosition.coerceIn(0, Listen.entries.size - 1)],
            spoken = spoken.selectedItemPosition.let { if (it <= 0) null else spokenLangs.getOrNull(it - 1) },
            model = models.getOrElse(model.selectedItemPosition) { "Tiny" },
            textSize = textSize.selectedItemPosition.coerceIn(0, 2),
            showOriginal = showOriginal.isChecked,
        )
    }

    private fun onChoicesChanged() {
        val c = readChoices()
        val before = current
        if (c == before) return
        current = c
        Prefs.save(this, c)
        updateEnabled()
        if (before != null && before.target != c.target) refreshModel()
        if (before != null) CaptionService.instance?.settingsChanged(before, c)
    }

    private fun updateEnabled() {
        val on = subtitles.isChecked
        subtitleOptions.visibility = if (on) View.VISIBLE else View.GONE
        listenNote.visibility = if (listen.selectedItemPosition == Listen.SYSTEM.ordinal) View.VISIBLE else View.GONE
        // Without the engine there are no subtitles; translating the screen still works.
        val error = engineError
        engineNote.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) {
            engineNote.text = getString(R.string.engine_failed, error)
            subtitles.isEnabled = false
        }
    }

    private fun updateState() {
        val on = CaptionService.running
        stateText.setText(if (on) R.string.state_on else R.string.state_off)
        stateNote.setText(if (on) R.string.state_on_note else R.string.state_off_note)
        stateDot.setBackgroundResource(if (on) R.drawable.dot_on else R.drawable.dot_off)
        toggle.setText(if (on) R.string.stop else R.string.start)
        toggle.setBackgroundResource(if (on) R.drawable.bg_button_secondary else R.drawable.bg_button_primary)
        toggle.setTextColor(getColor(if (on) R.color.danger else R.color.on_accent))
    }

    private fun onToggle() {
        if (CaptionService.running) {
            startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
            return
        }
        // The bubble lives over other apps: without this permission there is nothing to tap.
        if (!Settings.canDrawOverlays(this)) {
            askOverlay()
            return
        }
        val c = readChoices()
        val missing = Launch.missingPermissions(this, c, notifications = !notificationsAsked)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        status(getString(R.string.asking_sharing))
        startActivityForResult(Launch.consent(this), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_PERMISSIONS -> {
                notificationsAsked = true
                if (subtitles.isChecked && !micGranted) {
                    // Subtitles wait for the microphone; translating the screen does not.
                    subtitles.isChecked = false
                    status(getString(R.string.need_microphone), error = true)
                }
                onToggle()
            }
            REQ_MICROPHONE -> if (micGranted) {
                onChoicesChanged()
            } else {
                subtitles.isChecked = false
                status(getString(R.string.need_microphone), error = true)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            status("")
            Launch.start(this, resultCode, data)
        } else {
            status(getString(R.string.projection_denied), error = true)
        }
    }

    private fun askOverlay() {
        if (overlayAsked && startPending) return
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.overlay_allow) { _, _ ->
                overlayAsked = true
                startPending = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Whether the target language's model is on the phone, and a button to download it now. */
    private fun refreshModel() {
        val lang = readChoices().target
        if (downloading == lang) return
        thread(name = "hm-model-check") {
            val ready = Translations.isDownloaded(lang)
            handler.post {
                if (isDestroyed || readChoices().target != lang || downloading == lang) return@post
                modelStatus.text = getString(if (ready) R.string.model_ready else R.string.model_missing, Languages.name(lang))
                download.visibility = if (ready) View.GONE else View.VISIBLE
            }
        }
    }

    private fun downloadTarget() {
        val lang = readChoices().target
        downloading = lang
        download.isEnabled = false
        modelStatus.text = getString(R.string.model_downloading, Languages.name(lang))
        thread(name = "hm-model-download") {
            val error = try {
                Translations.download(lang)
                if (lang != "en") Translations.download("en")
                null
            } catch (e: Exception) {
                AppLog.w("main: $lang model not downloaded", e)
                e.message ?: e.toString()
            }
            handler.post {
                downloading = null
                download.isEnabled = true
                if (isDestroyed) return@post
                if (error != null) {
                    modelStatus.text = getString(R.string.model_failed, Languages.name(lang))
                } else {
                    refreshModel()
                }
            }
        }
    }

    /** Android 13: asks to put Hammurabi's tile in Quick Settings, to turn it on from any app. */
    private fun askToAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, CaptionTile::class.java),
            getString(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_notification),
            mainExecutor,
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                -> status(getString(R.string.tile_added), ttlMs = 6_000L)
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
                else -> status(getString(R.string.tile_manual), ttlMs = 10_000L)
            }
        }
    }

    private fun onEvent(e: JSONObject) {
        when (e.optString("t")) {
            "service" -> {
                updateState()
                if (!e.optBoolean("on")) stats.text = ""
            }
            "model" -> if (e.optString("lang") == readChoices().target) {
                when (e.optString("state")) {
                    "downloading" -> {
                        modelStatus.text = getString(R.string.model_downloading, Languages.name(e.optString("lang")))
                        download.visibility = View.GONE
                    }
                    else -> refreshModel()
                }
            }
            "stats" -> stats.text = getString(R.string.stats, e.optInt("asr_ms"), e.optString("device"))
            "status" -> if (e.optString("level") == "error") status(e.optString("text"), error = true)
            "progress" -> StatusText.of(this, e)?.let { status(it.text, ttlMs = it.ttlMs) }
        }
    }

    private fun status(text: String, error: Boolean = false, ttlMs: Long = 0L) {
        statusView.text = text
        statusView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        statusView.setTextColor(getColor(if (error) R.color.danger else R.color.text_dim))
        clearStatus?.let { handler.removeCallbacks(it) }
        clearStatus = null
        if (ttlMs > 0) {
            val r = Runnable { status("") }
            clearStatus = r
            handler.postDelayed(r, ttlMs)
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun modelLabel(i: Int): String {
        val m = modelInfo.optJSONObject(i) ?: return models[i]
        val label = "${m.optString("label", models[i])} · ${size(m.optLong("bytes"))}"
        return if (m.optBoolean("cached")) "$label · ${getString(R.string.downloaded)}" else label
    }

    private fun size(bytes: Long): String {
        val locale = Locale.getDefault()
        return if (bytes >= 1_000_000_000L) {
            String.format(locale, "%.1f GB", bytes / 1e9)
        } else {
            String.format(locale, "%d MB", bytes / 1_000_000L)
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_PROJECTION = 2
        const val REQ_MICROPHONE = 3
    }
}
