package dev.menzo.hammurabi

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The translated screen over another app, as a person sees it: a feed in front, its picture translated with
 * ML Kit and drawn in place. How it looks goes to files/layer.jpg, which CI shows.
 */
@RunWith(AndroidJUnit4::class)
class TranslationLayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun theFeedIsTranslatedInPlace() {
        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            .close()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!Settings.canDrawOverlays(context)) {
            assertTrue("the overlay permission", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
        context.startActivity(
            Intent(context, TextActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        SystemClock.sleep(3_000)

        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        var closed = false
        lateinit var layer: TranslationLayer
        instrumentation.runOnMainSync {
            layer = TranslationLayer(context, "tr", onClosed = { closed = true }, onLanguage = {})
            layer.show { ready.countDown() }
        }
        assertTrue("shown", ready.await(5, TimeUnit.SECONDS))
        // The layer is on, empty: the picture is of the feed alone.
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(shot)
        var error: String? = null
        var told: ScreenTranslator.Summary? = null
        // The layer hears all; the test hears the end too.
        val listener = object : ScreenTranslator.Listener by layer {
            override fun onDone(summary: ScreenTranslator.Summary) {
                layer.onDone(summary)
                told = summary
                done.countDown()
            }

            override fun onError(message: String) {
                layer.onError(message)
                error = message
                done.countDown()
            }
        }
        instrumentation.runOnMainSync {
            layer.picture(shot!!, shot.width)
            ScreenTranslator(context, Script.LATIN, "tr").run(shot, skipTop = 0, listener = listener)
        }
        assertTrue("translated in time", done.await(5, TimeUnit.MINUTES))
        assertNull("no error: $error", error)
        Log.i("hammurabi-test", "layer: ${told?.translated} of ${told?.read} translated in ${told?.ms} ms")
        assertTrue("the English posts are translated", (told?.translated ?: 0) >= 2)
        SystemClock.sleep(800)
        instrumentation.uiAutomation.takeScreenshot()?.let { s ->
            val small = Bitmap.createScaledBitmap(s, s.width / 2, s.height / 2, true)
            File(context.filesDir, "layer.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        }
        instrumentation.runOnMainSync { layer.close() }
        assertTrue(closed)
    }
}
