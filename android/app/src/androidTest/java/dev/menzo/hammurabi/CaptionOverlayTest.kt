package dev.menzo.hammurabi

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The subtitles over other apps: at the bottom middle, there only while there is something to show. */
@RunWith(AndroidJUnit4::class)
class CaptionOverlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun until(what: String, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!test()) {
            assertTrue("timed out waiting: $what", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    @Test
    fun captionsShowAtTheBottomMiddleAndOnlyWhileThereIsSomethingToShow() {
        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            .close()
        until("the overlay permission") { Settings.canDrawOverlays(context) }
        CaptionBus.last.clear()
        lateinit var overlay: CaptionOverlay
        instrumentation.runOnMainSync {
            overlay = CaptionOverlay(context, Choices(showOriginal = false))
            overlay.show()
        }
        SystemClock.sleep(500)
        assertNull("nothing to show, nothing shown", overlay.area)

        // A line about how things go shows until its time is up.
        instrumentation.runOnMainSync {
            CaptionBus.emit(JSONObject().put("t", "status").put("text", "Whisper Tiny: 25%").put("ttl_ms", 1_500))
        }
        until("the status line") { overlay.area != null }
        val r = overlay.area!!
        val screen = context.resources.displayMetrics
        assertTrue("in the lower half: ${r.toList()}", r[1] > screen.heightPixels / 2)
        assertTrue("in the middle: ${r.toList()}", abs((r[0] + r[2]) / 2 - screen.widthPixels / 2) <= 10)
        until("the status line to go") { overlay.area == null }

        // A sentence shows as heard, then as translated.
        val final = JSONObject().put("t", "final").put("id", 1).put("o", "See you tomorrow").put("same", false)
        instrumentation.runOnMainSync { CaptionBus.emit(JSONObject(final.toString()).put("tr", JSONObject.NULL)) }
        until("the sentence as heard") { overlay.area != null }
        instrumentation.runOnMainSync { CaptionBus.emit(JSONObject(final.toString()).put("tr", "Yarın görüşürüz")) }
        SystemClock.sleep(300)
        assertNotNull(overlay.area)
        // A post's translation, longer: how it looks goes to files/overlay.jpg (CI shows it).
        instrumentation.runOnMainSync {
            CaptionBus.emit(
                JSONObject().put("t", "final").put("id", 2).put("same", false)
                    .put("o", "I can't believe how fast this year went by. Thank you all for being part of this journey!")
                    .put("tr", "Bu yılın ne kadar hızlı geçtiğine inanamıyorum. Bu yolculuğun parçası olduğunuz için hepinize teşekkürler!"),
            )
        }
        SystemClock.sleep(1_000)
        instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
            val small = Bitmap.createScaledBitmap(shot, shot.width / 3, shot.height / 3, true)
            File(context.filesDir, "overlay.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }

        instrumentation.runOnMainSync { overlay.hide() }
        assertNull(overlay.area)
        CaptionBus.last.clear()
    }
}
