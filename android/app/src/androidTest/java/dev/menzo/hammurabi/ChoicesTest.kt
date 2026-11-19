package dev.menzo.hammurabi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The settings, saved and as the engine reads them; the languages; the lines that say how things go. */
@RunWith(AndroidJUnit4::class)
class ChoicesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun subtitlesAreOffAtFirstAndTheEngineOnlyListens() {
        val d = Choices()
        assertFalse(d.subtitles)
        assertEquals(Listen.SYSTEM, d.listen)
        assertEquals(Script.LATIN, d.script)
        val s = JSONObject(Choices(target = "tr", listen = Listen.MIC).settingsJson())
        assertEquals("Whisper", s.getString("asr_backend"))
        assertEquals("Microphone", s.getString("source"))
        // ML Kit translates what is heard, not the engine (which would download 1.7 GB).
        assertFalse(s.getBoolean("translate"))
        assertEquals("System", JSONObject(Choices(listen = Listen.SYSTEM).settingsJson()).getString("source"))
    }

    @Test
    fun settingsAreSavedAndLoaded() {
        val c = Choices(
            target = "de", subtitles = true, listen = Listen.MIC, spoken = "en", model = "Base",
            textSize = 2, showOriginal = true, script = Script.JAPANESE, live = true, region = Region.TOP,
        )
        Prefs.save(context, c)
        assertEquals(c, Prefs.load(context))
        Prefs.save(context, Choices())
        assertEquals(Choices(), Prefs.load(context))
    }

    @Test
    fun theWatchedAreaIsKept() {
        assertTrue(Prefs.area(context, Region.BOTTOM).contentEquals(floatArrayOf(0f, 0.5f, 1f, 1f)))
        Prefs.saveArea(context, floatArrayOf(0.1f, 0.6f, 0.9f, 0.8f))
        assertTrue(Prefs.area(context, Region.CUSTOM).contentEquals(floatArrayOf(0.1f, 0.6f, 0.9f, 0.8f)))
        // Too small to hold a line: the bottom of the screen instead.
        Prefs.saveArea(context, floatArrayOf(0.5f, 0.5f, 0.51f, 0.505f))
        assertTrue(Prefs.area(context, Region.CUSTOM).contentEquals(floatArrayOf(0f, 0.5f, 1f, 1f)))
    }

    @Test
    fun theBubbleKeepsItsPlace() {
        Prefs.saveBubble(context, false, 0.8f)
        assertEquals(false to 0.8f, Prefs.bubble(context))
        Prefs.saveBubble(context, true, 0.35f)
    }

    @Test
    fun translatingTheScreenAsksForNoMicrophone() {
        assertTrue(Launch.missingPermissions(context, Choices(subtitles = false), notifications = false).isEmpty())
    }

    @Test
    fun languagesAreMlKitsOwn() {
        assertEquals("en", Languages.supported("en-US"))
        assertEquals("tr", Languages.supported("tr"))
        assertEquals("he", Languages.supported("iw"))
        assertEquals("zh", Languages.supported("zh"))
        // Hindi in Latin letters is not what its model reads; "und" is no language.
        assertNull(Languages.supported("hi-Latn"))
        assertNull(Languages.supported("und"))
        assertNull(Languages.supported(""))
        assertNotNull(Languages.supported(Languages.defaultTarget()))
        val all = Languages.byName()
        assertTrue("about 59 languages: ${all.size}", all.size > 50 && "tr" in all && "en" in all)
        assertEquals("Turkish", Languages.name("tr", java.util.Locale.ENGLISH))
    }

    @Test
    fun linesSayHowThingsGo() {
        val download = JSONObject().put("t", "progress").put("model", "Whisper Tiny").put("done", 50).put("total", 200)
        val line = StatusText.of(context, download)!!
        assertTrue(line.text, line.text.contains("25"))
        assertFalse(line.error)
        assertTrue("a download's line goes once it stops telling", line.ttlMs in 1..60_000)
        val running = StatusText.of(context, JSONObject().put("t", "state").put("state", "running"))!!
        assertEquals(context.getString(R.string.state_running), running.text)
        val error = StatusText.of(context, JSONObject().put("t", "status").put("level", "error").put("text", "boom"))!!
        assertTrue(error.error)
        assertNull(StatusText.of(context, JSONObject().put("t", "stats")))
    }
}
