package dev.menzo.hammurabi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Automatic subtitles translated with ML Kit as the engine gives them: a sentence shows as heard at once,
 * then translated; one in the target language already is marked so, not translated.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleTranslatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun final(id: Int, text: String, lang: String) =
        JSONObject().put("t", "final").put("id", id).put("o", text).put("tr", JSONObject.NULL)
            .put("same", false).put("lang", lang)

    @Test
    fun aSentenceIsShownAsHeardThenTranslated() {
        // Added to on the main thread, read on this one.
        val seen = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val translated = CountDownLatch(1)
        val turkish = CountDownLatch(1)
        lateinit var t: SubtitleTranslator
        instrumentation.runOnMainSync {
            t = SubtitleTranslator(context, "tr")
            val emit: (JSONObject) -> Unit = { e ->
                seen += e
                if (e.optInt("id") == 1 && !e.isNull("tr")) translated.countDown()
                if (e.optInt("id") == 2 && e.optBoolean("same")) turkish.countDown()
            }
            t.handle(final(1, "Where are you going tonight?", "en"), emit)
            t.handle(final(2, "Bu akşam nereye gidiyorsun?", "tr"), emit)
        }
        assertTrue("translated: $seen", translated.await(5, TimeUnit.MINUTES))
        assertTrue("Turkish marked as such: $seen", turkish.await(1, TimeUnit.MINUTES))
        // As heard first, translated after.
        val first = seen.first { it.optInt("id") == 1 }
        assertTrue(first.isNull("tr"))
        val tr = seen.last { it.optInt("id") == 1 }.getString("tr")
        assertTrue("into Turkish: $tr", tr.isNotBlank() && !ScreenText.onlyRepeats(tr, "Where are you going tonight?"))
        assertEquals(0, seen.count { it.optInt("id") == 2 && !it.isNull("tr") })
        instrumentation.runOnMainSync { t.close() }
    }
}
