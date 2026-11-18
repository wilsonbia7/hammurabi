package dev.menzo.hammurabi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * The engine on Android itself, through the app's own native calls: it loads, tells what it offers, and reads
 * the screen end to end (the OCR models from the APK, ONNX Runtime, the page reader, the engine), from a
 * picture drawn as a phone's feed would be. Translation is off: it would download 1.7 GB.
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
        val targets = info.getJSONArray("target")
        assertTrue((0 until targets.length()).any { targets.getString(it) == "tr" })
        assertTrue(info.getJSONArray("models").length() > 0)
    }

    @Test
    fun badSettingsDoNotStart() {
        assertFalse(Native.start("{not json"))
    }

    @Test
    fun theOcrModelsComeOutOfTheApk() {
        assertTrue(OcrModels.ensure(context))
        val dir = File(context.filesDir, "ocr")
        for (name in listOf("ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx")) {
            assertTrue(name, File(dir, name).length() > 1_000_000)
        }
        // A second time it finds them in place.
        assertTrue(OcrModels.ensure(context))
    }

    @Test
    fun foreignTextOnTheScreenIsReadOnceAndTurkishIsLeft() {
        assertTrue(OcrModels.ensure(context))
        val (w, h) = 886 to 1920
        val frame = feed(w, h)
        // Twice: the second start in one process is where the voice detector and the reader once broke.
        for (round in 1..2) {
            assertTrue(Native.start(Choices(source = Source.SCREEN, translate = false, target = "tr").settingsJson()))
            val finals = mutableListOf<String>()
            var running = false
            var error: String? = null
            val deadline = SystemClock.uptimeMillis() + 300_000
            while (SystemClock.uptimeMillis() < deadline && error == null && finals.none { "believe" in it }) {
                assertTrue("a reader is there for the pictures", Native.screenFrame(frame, w, h, w * 4, 0, 0, 0, 0))
                while (true) {
                    val e = JSONObject(Native.poll(0) ?: break)
                    Log.i("hammurabi-test", "round $round: $e")
                    when (e.optString("t")) {
                        "final" -> finals += e.getString("o")
                        "state" -> running = running || e.optString("state") == "running"
                        "status" -> if (e.optString("level") == "error") error = e.optString("text")
                    }
                }
                SystemClock.sleep(150)
            }
            assertNull(error)
            assertTrue("the engine runs", running)
            assertTrue("the English post is read: $finals", finals.any { "believe how fast this year" in it })
            assertTrue("the Turkish post and the buttons are left: $finals", finals.none { "arkada" in it || "Payla" in it })
            // Read once, not again while it stays.
            repeat(20) {
                Native.screenFrame(frame, w, h, w * 4, 0, 0, 0, 0)
                SystemClock.sleep(150)
            }
            var more = 0
            while (true) {
                val e = JSONObject(Native.poll(0) ?: break)
                if (e.optString("t") == "final" && "believe" in e.getString("o") &&
                    finals.none { it == e.getString("o") }
                ) {
                    more++
                }
            }
            assertEquals("given once", 0, more)
            Native.stop()
        }
    }

    @Test
    fun stoppingDoesNotWaitForAPictureBeingRead() {
        assertTrue(OcrModels.ensure(context))
        val (w, h) = 886 to 1920
        val frame = feed(w, h)
        assertTrue(Native.start(Choices(source = Source.SCREEN, translate = false, target = "tr").settingsJson()))
        // The first picture is read at once, which takes a while.
        val reading = Thread { Native.screenFrame(frame, w, h, w * 4, 0, 0, 0, 0) }.apply { start() }
        SystemClock.sleep(300)
        val t = SystemClock.uptimeMillis()
        Native.stop()
        val stopped = SystemClock.uptimeMillis() - t
        Log.i("hammurabi-test", "stopped in $stopped ms, the reading still going: ${reading.isAlive}")
        assertTrue("stopped in $stopped ms", stopped < 2_000)
        // And a new start does not wait for it either; the old reading's end goes nowhere.
        assertTrue(Native.start(Choices(source = Source.SCREEN, translate = false, target = "tr").settingsJson()))
        reading.join()
        assertTrue("the new run reads", Native.screenFrame(frame, w, h, w * 4, 0, 0, 0, 0))
    }

    @Test
    fun ourOwnCaptionsAreNotRead() {
        assertTrue(OcrModels.ensure(context))
        val (w, h) = 886 to 1920
        val frame = feed(w, h, captionAt = 1500)
        assertTrue(Native.start(Choices(source = Source.SCREEN, translate = false, target = "tr").settingsJson()))
        val finals = mutableListOf<String>()
        val deadline = SystemClock.uptimeMillis() + 300_000
        // The caption box masked; the post above it is read.
        while (SystemClock.uptimeMillis() < deadline && finals.none { "believe" in it }) {
            Native.screenFrame(frame, w, h, w * 4, 20, 1480, w - 20, 1700)
            while (true) {
                val e = JSONObject(Native.poll(0) ?: break)
                if (e.optString("t") == "final") finals += e.getString("o")
            }
            SystemClock.sleep(150)
        }
        assertTrue("the post is read: $finals", finals.any { "believe" in it })
        assertTrue("our caption is not: $finals", finals.none { "caption" in it })
    }

    /** A feed as the screen capture gives it: RGBA rows in a direct buffer. */
    private fun feed(w: Int, h: Int, captionAt: Int? = null): ByteBuffer {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(5, 5, 5); textSize = 36f }
        val grey = Paint(ink).apply { color = Color.rgb(101, 103, 107); textSize = 28f }
        val bold = Paint(ink).apply { isFakeBoldText = true; textSize = 34f }
        canvas.drawText("John Carter", 130f, 345f, bold)
        canvas.drawText("3 sa · Herkese açık", 130f, 386f, grey)
        listOf(
            "I can't believe how fast this year went by.",
            "Thank you all for being part of this",
            "journey, see you next year!",
        ).forEachIndexed { i, row -> canvas.drawText(row, 30f, 460f + i * 46, ink) }
        canvas.drawText("Beğen", 90f, 680f, grey)
        canvas.drawText("Yorum Yap", 330f, 680f, grey)
        canvas.drawText("Paylaş", 650f, 680f, grey)
        canvas.drawText("Ayşe Yılmaz", 130f, 845f, bold)
        listOf(
            "Bugün arkadaşlarla harika bir akşam geçirdik,",
            "herkese çok teşekkür ederim!",
        ).forEachIndexed { i, row -> canvas.drawText(row, 30f, 960f + i * 46, ink) }
        if (captionAt != null) {
            val box = Paint().apply { color = Color.argb(200, 0, 0, 0) }
            canvas.drawRect(40f, captionAt.toFloat(), w - 40f, captionAt + 160f, box)
            val white = Paint(ink).apply { color = Color.WHITE; textSize = 40f }
            canvas.drawText("Our own caption should not", 120f, captionAt + 60f, white)
            canvas.drawText("be read again by the reader", 120f, captionAt + 120f, white)
        }
        val buffer = ByteBuffer.allocateDirect(w * h * 4)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        return buffer
    }
}
