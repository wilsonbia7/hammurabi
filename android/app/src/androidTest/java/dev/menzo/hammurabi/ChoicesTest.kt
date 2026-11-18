package dev.menzo.hammurabi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The choices, saved and as the engine reads them; the lines that say how things go. */
@RunWith(AndroidJUnit4::class)
class ChoicesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theScreenIsTheFirstChoiceAndReadsWithoutSpeechOrMicrophone() {
        assertEquals(Source.SCREEN, Source.entries.first())
        assertEquals(Source.SCREEN, Choices().source)
        val s = JSONObject(Choices(source = Source.SCREEN, target = "tr").settingsJson())
        assertEquals("ScreenText", s.getString("asr_backend"))
        assertEquals("tr", s.getString("target_language"))
        assertFalse(Source.SCREEN.listens)
        assertTrue(Source.SCREEN.shares)
        for (source in listOf(Source.SYSTEM, Source.MIC, Source.BOTH)) {
            assertEquals("Whisper", JSONObject(Choices(source = source).settingsJson()).getString("asr_backend"))
            assertTrue(source.listens)
        }
        assertFalse(Source.MIC.shares)
    }

    @Test
    fun choicesAreSavedAndLoaded() {
        val c = Choices(source = Source.SYSTEM, spoken = "en", translate = true, target = "tr", model = "Base")
        Prefs.save(context, c)
        assertEquals(c, Prefs.load(context))
        Prefs.save(context, Choices())
    }

    @Test
    fun theReadingScreenAsksForNoMicrophone() {
        // Notifications aside, nothing for the screen; the microphone for the others unless granted.
        assertTrue(Launch.missingPermissions(context, Choices(source = Source.SCREEN), notifications = false).isEmpty())
    }

    @Test
    fun linesSayHowThingsGo() {
        val download = JSONObject().put("t", "progress").put("model", "MADLAD").put("done", 50).put("total", 200)
        val line = StatusText.of(context, download, Source.SCREEN)!!
        assertTrue(line.text, line.text.contains("25"))
        assertFalse(line.error)
        assertTrue("a download's line goes once it stops telling", line.ttlMs in 1..60_000)
        val running = StatusText.of(context, JSONObject().put("t", "state").put("state", "running"), Source.SCREEN)!!
        assertEquals(context.getString(R.string.state_reading), running.text)
        val error = StatusText.of(context, JSONObject().put("t", "status").put("level", "error").put("text", "boom"), null)!!
        assertTrue(error.error)
        assertNull(StatusText.of(context, JSONObject().put("t", "stats"), null))
    }
}
