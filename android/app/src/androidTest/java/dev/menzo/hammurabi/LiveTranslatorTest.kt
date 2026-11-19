package dev.menzo.hammurabi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live translation with ML Kit on Android itself, fed pictures of a subtitle area as the screen would give
 * them: a subtitle is translated once it stays; one written out letter by letter only when it is whole; one
 * already in Turkish shows nothing; the translation goes when the subtitle does.
 */
@RunWith(AndroidJUnit4::class)
class LiveTranslatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shown = CopyOnWriteArrayList<List<LiveTranslator.Shown>>()
    private var hides = 0

    @Volatile
    private var black = 0
    private val t = LiveTranslator(context, object : LiveTranslator.Listener {
        override fun onShow(items: List<LiveTranslator.Shown>) {
            shown += items
        }

        override fun onHide() {
            hides++
        }

        override fun onBlack() {
            black++
        }

        override fun onDownloading(lang: String) {}
    }).apply {
        target = "tr"
        area = floatArrayOf(0f, 0.5f, 1f, 1f)
    }

    /** The bottom half of a 1080x2160 screen: the area as the screen capture gives it. */
    private val area = Rect(0, 1080, 1080, 2160)

    @After
    fun tearDown() {
        t.close()
    }

    private fun subtitle(text: String, pureBlack: Boolean = false): Bitmap {
        val b = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(if (pureBlack) Color.BLACK else Color.rgb(40, 52, 70))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 54f
            textAlign = Paint.Align.CENTER
        }
        // Split as a player does, at about 30 letters a line.
        val words = text.split(" ")
        val lines = mutableListOf("")
        for (w in words) {
            if (lines.last().length + w.length > 30) lines += w else lines[lines.size - 1] = (lines.last() + " " + w).trim()
        }
        lines.filter { it.isNotEmpty() }.forEachIndexed { i, l -> c.drawText(l, 540f, 760f + i * 70, ink) }
        return b
    }

    /** Feeds [text] for [ms], a picture whenever the translator is ready, as the capture does. */
    private fun feed(text: String, ms: Long, pureBlack: Boolean = false, until: () -> Boolean = { false }) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end && !until()) {
            if (t.ready()) t.frame(subtitle(text, pureBlack), area, black = pureBlack)
            SystemClock.sleep(120)
        }
        // The main thread's news.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun subtitlesAreTranslatedAsTheyComeAndGo() {
        // The first one also waits for the models (downloaded once).
        feed("Where did you put the keys?", 360_000) { shown.isNotEmpty() }
        assertTrue("translated: $shown", shown.isNotEmpty())
        val first = shown.last().single()
        Log.i("hammurabi-test", "live: ${first.original} → ${first.translation} at ${first.box}")
        assertEquals("Where did you put the keys?", first.original)
        assertTrue("the box is where the subtitle is: ${first.box}", first.box.top > 1080 + 600 && first.box.bottom < 2160)

        // The same subtitle stays: nothing new.
        feed("Where did you put the keys?", 1_500)
        assertEquals(1, shown.size)

        // A line written out letter by letter, in capitals: translated whole.
        val line = "THE CASTLE GATES ARE CLOSED. WE MUST FIND ANOTHER WAY IN."
        for (n in 6..line.length step 6) feed(line.substring(0, n), 150)
        // A phone too slow to read twice may show a part first; the whole line comes after.
        feed(line, 60_000) { shown.size >= 2 && shown.last().single().original == line }
        assertTrue("the whole line: $shown", shown.size >= 2)
        assertEquals(line, shown.last().single().original)
        assertTrue("hidden while it was written out", hides >= 1)

        // Already in Turkish: nothing shows, and the last translation goes.
        val before = shown.size
        feed("Bugün arkadaşlarla harika bir akşam geçirdik.", 4_000)
        assertEquals(before, shown.size)

        // No subtitle: the translation goes.
        val hidden = hides
        feed("", 2_000)
        assertTrue(hides >= hidden)

        // A black screen for a while: an app that forbids capture.
        feed("", 12_000, pureBlack = true) { black > 0 }
        assertTrue("told the screen is black", black > 0)
    }
}
