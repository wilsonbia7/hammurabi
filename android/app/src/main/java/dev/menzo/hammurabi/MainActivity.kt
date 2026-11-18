package dev.menzo.hammurabi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
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

/** The one screen: captions on top, the button, and the choices below. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val captions = Captions()

    private lateinit var captionsView: TextView
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var source: Spinner
    private lateinit var spoken: Spinner
    private lateinit var translate: Switch
    private lateinit var target: Spinner
    private lateinit var translateNote: TextView
    private lateinit var model: Spinner
    private lateinit var overlay: Switch
    private lateinit var showOriginal: Switch
    private lateinit var threads: Spinner
    private lateinit var modelViews: List<View>
    private lateinit var screenNote: TextView
    private lateinit var projectionNote: TextView
    private lateinit var addTile: Button

    // Fallbacks for when the engine does not load; `Native.info` replaces them.
    private var sourceLangs = listOf("en", "tr")
    private var targetLangs = listOf("tr", "en")
    private var models = listOf("Tiny", "Base", "Small")
    private var modelInfo = JSONArray()
    private var translatorBytes = 0L
    private val threadChoices = listOf(0, 2, 4, 6, 8)

    private var current: Choices? = null
    private var defaultStatusColor = Color.GRAY

    /** The last speech model run and translation, for the line under the status. */
    private var hearing: String? = null
    private var translating: String? = null
    private var clearStatus: Runnable? = null
    private var notificationsAsked = false
    private var overlayAsked = false

    private val listener: (JSONObject) -> Unit = { onEvent(it) }

    /** Lets captions whose time is up go, between events. */
    private val tick = object : Runnable {
        override fun run() {
            renderCaptions()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        captionsView = requireViewById(R.id.captions)
        toggle = requireViewById(R.id.toggle)
        statusView = requireViewById(R.id.status)
        statsView = requireViewById(R.id.stats)
        source = requireViewById(R.id.source)
        spoken = requireViewById(R.id.spoken)
        translate = requireViewById(R.id.translate)
        target = requireViewById(R.id.target)
        translateNote = requireViewById(R.id.translate_note)
        model = requireViewById(R.id.model)
        overlay = requireViewById(R.id.overlay)
        showOriginal = requireViewById(R.id.show_original)
        threads = requireViewById(R.id.threads)
        modelViews = listOf(requireViewById(R.id.label_model), model, requireViewById(R.id.model_note))
        screenNote = requireViewById(R.id.screen_note)
        projectionNote = requireViewById(R.id.projection_note)
        addTile = requireViewById(R.id.add_tile)
        defaultStatusColor = statusView.currentTextColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.visibility = View.VISIBLE
            addTile.setOnClickListener { askToAddTile() }
        }

        val error = Engine.setup(this)
        if (error == null) {
            readInfo()
        } else {
            status(getString(R.string.setup_failed, error), error = true)
            toggle.isEnabled = false
        }
        fill(Prefs.load(this))
        toggle.setOnClickListener { onToggle() }
    }

    override fun onStart() {
        super.onStart()
        CaptionBus.add(listener)
        // What happened while this screen was away.
        CaptionBus.last.values.toList().forEach { showMeta(it) }
        updateToggle()
        handler.post(tick)
    }

    override fun onStop() {
        CaptionBus.remove(listener)
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun readInfo() {
        val info = JSONObject(Native.info())
        sourceLangs = info.getJSONArray("source").strings()
        targetLangs = info.getJSONArray("target").strings()
        modelInfo = info.getJSONArray("models")
        models = (0 until modelInfo.length()).map { modelInfo.getJSONObject(it).getString("id") }
        translatorBytes = info.optJSONObject("translator")?.optLong("bytes") ?: 0L
    }

    private fun fill(c: Choices) {
        // In the order of `Source`.
        source.adapter = adapter(
            listOf(
                getString(R.string.source_screen),
                getString(R.string.source_system),
                getString(R.string.source_mic),
                getString(R.string.source_both),
            ),
        )
        source.setSelection(c.source.ordinal)
        spoken.adapter = adapter(listOf(getString(R.string.spoken_auto)) + sourceLangs.map { languageName(it) })
        spoken.setSelection(c.spoken?.let { sourceLangs.indexOf(it) + 1 } ?: 0)
        translate.isChecked = c.translate
        target.adapter = adapter(targetLangs.map { languageName(it) })
        target.setSelection(targetLangs.indexOf(c.target).coerceAtLeast(0))
        model.adapter = adapter(models.indices.map { modelLabel(it) })
        model.setSelection(models.indexOf(c.model).coerceAtLeast(0))
        overlay.isChecked = c.overlay
        showOriginal.isChecked = c.showOriginal
        threads.adapter = adapter(threadChoices.map { if (it == 0) getString(R.string.threads_auto) else it.toString() })
        threads.setSelection(threadChoices.indexOf(c.threads).coerceAtLeast(0))
        translateNote.text = getString(R.string.translate_note, size(translatorBytes))
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
        listOf(source, spoken, target, model, threads).forEach { it.onItemSelectedListener = changed }
        listOf(translate, overlay, showOriginal).forEach { s -> s.setOnCheckedChangeListener { _, _ -> onChoicesChanged() } }
    }

    private fun readChoices() = Choices(
        source = Source.entries[source.selectedItemPosition.coerceIn(0, Source.entries.size - 1)],
        spoken = spoken.selectedItemPosition.let { if (it <= 0) null else sourceLangs.getOrNull(it - 1) },
        translate = translate.isChecked,
        target = targetLangs.getOrElse(target.selectedItemPosition) { "en" },
        model = models.getOrElse(model.selectedItemPosition) { "Tiny" },
        overlay = overlay.isChecked,
        showOriginal = showOriginal.isChecked,
        threads = threadChoices.getOrElse(threads.selectedItemPosition) { 0 },
    )

    private fun onChoicesChanged() {
        val c = readChoices()
        val before = current
        if (c == before) return
        current = c
        Prefs.save(this, c)
        updateEnabled()
        renderCaptions()
        // Languages, translation and the model change while listening; what to listen to at the next start.
        if (CaptionService.running && before != null && c.source == before.source &&
            c.settingsJson() != before.settingsJson()
        ) {
            Engine.run { Native.reconfigure(c.settingsJson()) }
        }
    }

    private fun updateEnabled() {
        target.isEnabled = translate.isChecked
        translateNote.visibility = if (translate.isChecked) View.VISIBLE else View.GONE
        // Reading the screen uses no speech model, and its originals are on screen already.
        val source = readChoices().source
        val screen = source == Source.SCREEN
        modelViews.forEach { it.visibility = if (screen) View.GONE else View.VISIBLE }
        showOriginal.visibility = if (screen) View.GONE else View.VISIBLE
        screenNote.visibility = if (screen) View.VISIBLE else View.GONE
        projectionNote.visibility = if (source == Source.SYSTEM || source == Source.BOTH) View.VISIBLE else View.GONE
    }

    private fun onToggle() {
        if (CaptionService.running) {
            startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
            return
        }
        val c = readChoices()
        val missing = Launch.missingPermissions(this, c, notifications = !notificationsAsked)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        if (c.overlay && !overlayAsked && !Settings.canDrawOverlays(this)) {
            askOverlay()
            return
        }
        if (c.source.shares) {
            startActivityForResult(Launch.consent(this), REQ_PROJECTION)
            return
        }
        launch(c, 0, null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        notificationsAsked = true
        if (!readChoices().source.listens ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            onToggle()
        } else {
            status(getString(R.string.need_microphone), error = true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            launch(readChoices(), resultCode, data)
        } else {
            status(getString(R.string.projection_denied), error = true)
        }
    }

    private fun askOverlay() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.overlay_allow) { _, _ ->
                overlayAsked = true
                // Back from there, Start goes on with the overlay.
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.overlay_skip) { _, _ ->
                overlayAsked = true
                onToggle()
            }
            .show()
    }

    private fun launch(c: Choices, projectionCode: Int, projectionData: Intent?) {
        captions.apply(JSONObject().put("t", "clear"))
        renderCaptions()
        hearing = null
        translating = null
        showSpeed()
        Launch.start(this, c, projectionCode, projectionData)
    }

    /** Android 13: asks to put Hammurabi's tile in Quick Settings, to turn captions on from any app. */
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
                -> status(getString(R.string.tile_added), ttlMs = 5_000L)
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
                else -> status(getString(R.string.tile_manual), ttlMs = 8_000L)
            }
        }
    }

    private fun onEvent(event: JSONObject) {
        when (event.optString("t")) {
            "final", "live", "clear" -> {
                captions.apply(event)
                renderCaptions()
            }
            else -> showMeta(event)
        }
    }

    private fun showMeta(e: JSONObject) {
        when (e.optString("t")) {
            "state" -> {
                updateToggle()
                if (e.optString("state") == "idle") {
                    // An error that ended captions stays readable.
                    if (statusView.currentTextColor != ERROR) status("")
                    captions.apply(JSONObject().put("t", "clear"))
                    renderCaptions()
                    return
                }
            }
            "stats" -> {
                hearing = getString(R.string.stats, e.optInt("asr_ms"), e.optString("device"))
                showSpeed()
                return
            }
            "translated" -> {
                translating = getString(R.string.translated_in, e.optLong("ms"))
                showSpeed()
                return
            }
        }
        // While captions run, the line says what they are made of; before, what is chosen.
        val running = current?.source
        StatusText.of(this, e, running)?.let {
            // Here "Reading the screen" stays while it does.
            status(it.text, it.error, if (e.optString("state") == "running") 0L else it.ttlMs)
        }
    }

    private fun showSpeed() {
        statsView.text = listOfNotNull(hearing, translating).joinToString(" · ")
    }

    private fun status(text: String, error: Boolean = false, ttlMs: Long = 0L) {
        statusView.text = text
        statusView.setTextColor(if (error) ERROR else defaultStatusColor)
        clearStatus?.let { handler.removeCallbacks(it) }
        clearStatus = null
        if (ttlMs > 0) {
            val r = Runnable { statusView.text = "" }
            clearStatus = r
            handler.postDelayed(r, ttlMs)
        }
    }

    private fun updateToggle() {
        toggle.setText(if (CaptionService.running) R.string.stop else R.string.start)
    }

    private fun renderCaptions() {
        // What was read off the screen is shown too: here it tells what the reader sees.
        val text = captions.text(sp(24f), sp(17f), showOriginal.isChecked || current?.source == Source.SCREEN)
        captionsView.text = text.ifEmpty { getString(R.string.captions_hint) }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /** In the phone's language: "İngilizce", "Çince (Geleneksel)". */
    private fun languageName(code: String): String {
        val locale = Locale.getDefault()
        return Locale.forLanguageTag(code).getDisplayName(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
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

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_PROJECTION = 2
        val ERROR = Color.parseColor("#e5484d")
    }
}
