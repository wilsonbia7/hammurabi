package dev.menzo.hammurabi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The listening engine on Android itself, through the app's own native calls: it loads, tells what it
 * offers, and starts and stops with the settings automatic subtitles give it.
 */
@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        assertNull("the engine loads", Engine.setup(context))
    }

    @After
    fun tearDown() {
        Native.stop()
    }

    @Test
    fun theEngineTellsWhatItOffers() {
        val info = JSONObject(Native.info())
        val heard = info.getJSONArray("source")
        assertTrue((0 until heard.length()).any { heard.getString(it) == "en" })
        assertTrue(info.getJSONArray("models").length() > 0)
    }

    @Test
    fun badSettingsDoNotStart() {
        assertFalse(Native.start("{not json"))
    }

    @Test
    fun subtitlesSettingsStartAndStop() {
        // Started and stopped at once: the speech model is not downloaded for this.
        assertTrue(Native.start(Choices(target = "tr").settingsJson()))
        val samples = ShortArray(1_600)
        assertTrue(Native.feed(samples, samples.size, 16_000, true))
        Native.stop()
        assertTrue("started again in the same process", Native.start(Choices(listen = Listen.MIC).settingsJson()))
    }
}
