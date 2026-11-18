package dev.menzo.hammurabi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The screen translated end to end with ML Kit on Android itself, from a picture drawn as a feed: its text
 * read, each block's language told, the English translated into Turkish and the Turkish left as it is.
 * The first run downloads the English and Turkish models (about 30 MB each).
 */
@RunWith(AndroidJUnit4::class)
class ScreenTranslatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** What the translator told, as it told it. */
    private class Heard : ScreenTranslator.Listener {
        val done = CountDownLatch(1)
        var blocks: List<ScreenTranslator.Block> = emptyList()
        val translated = mutableListOf<ScreenTranslator.Block>()
        var downloading = mutableListOf<String>()
        var summary: ScreenTranslator.Summary? = null
        var error: String? = null

        override fun onRead(blocks: List<ScreenTranslator.Block>) {
            this.blocks = blocks
        }

        override fun onDownloading(lang: String) {
            downloading += lang
        }

        override fun onTranslated(block: ScreenTranslator.Block) {
            translated += block
        }

        override fun onDone(summary: ScreenTranslator.Summary) {
            this.summary = summary
            done.countDown()
        }

        override fun onError(message: String) {
            error = message
            done.countDown()
        }
    }

    private fun feed(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 10, 10); textSize = 52f }
        canvas.drawText("12:45", 40f, 60f, ink)
        listOf("Where did you put the keys?", "We have to leave before dawn.")
            .forEachIndexed { i, row -> canvas.drawText(row, 60f, 420f + i * 70, ink) }
        canvas.drawText("Bugün arkadaşlarla harika bir akşam", 60f, 900f, ink)
        canvas.drawText("geçirdik, herkese teşekkürler!", 60f, 970f, ink)
        canvas.drawText("♥ 1.2K   234", 60f, 1300f, ink)
        return bitmap
    }

    @Test
    fun englishIsTranslatedTurkishIsLeftNumbersAreSkipped() {
        val heard = Heard()
        ScreenTranslator(context, Script.LATIN, "tr").run(feed(), skipTop = 90, listener = heard)
        assertTrue("finished in time", heard.done.await(5, TimeUnit.MINUTES))
        assertNull("no error: ${heard.error}", heard.error)
        val texts = heard.blocks.map { "${it.lang}: ${it.text} → ${it.translation}" }
        Log.i("hammurabi-test", "blocks: $texts, downloaded ${heard.downloading}, ${heard.summary?.ms} ms")
        val english = heard.blocks.firstOrNull { "keys" in it.text }
        assertNotNull("the English is read: $texts", english)
        assertEquals("en", english!!.lang)
        val tr = english.translation
        assertNotNull("the English is translated: $texts", tr)
        assertTrue("into other words: $tr", !Captions.alike(tr!!, english.text))
        val turkish = heard.blocks.firstOrNull { "arkada" in it.text }
        assertNotNull("the Turkish is read: $texts", turkish)
        assertEquals("tr", turkish!!.lang)
        assertNull("and left: $texts", turkish.translation)
        assertTrue("the status bar's time and the counts are skipped: $texts", heard.blocks.none { "12:45" in it.text || "1.2K" in it.text })
        assertEquals(1, heard.summary!!.translated)
        assertTrue(heard.summary!!.same >= 1)

        // A second time the models are there: nothing downloads, and it is quick.
        val again = Heard()
        ScreenTranslator(context, Script.LATIN, "tr").run(feed(), skipTop = 90, listener = again)
        assertTrue(again.done.await(2, TimeUnit.MINUTES))
        assertTrue("downloaded again: ${again.downloading}", again.downloading.isEmpty())
        Log.i("hammurabi-test", "again in ${again.summary?.ms} ms")
    }

    @Test
    fun aCancelledTranslationTellsNothingMore() {
        val heard = Heard()
        val t = ScreenTranslator(context, Script.LATIN, "tr")
        t.run(feed(), skipTop = 90, listener = heard)
        t.cancel()
        assertTrue("nothing told after cancelling", !heard.done.await(15, TimeUnit.SECONDS))
        assertTrue(heard.translated.isEmpty())
    }
}
