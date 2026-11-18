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

/** The captions over other apps: at the bottom middle, there only while there is something to show. */
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
            overlay = CaptionOverlay(context, showOriginal = false, translatedOnly = true, source = Source.SCREEN)
            overlay.show()
        }
        SystemClock.sleep(500)
        assertNull("nothing to show, nothing shown", OverlayArea.rect)

        // A line about how things go shows until its time is up.
        instrumentation.runOnMainSync {
            CaptionBus.emit(JSONObject().put("t", "status").put("text", "MADLAD: 25%").put("ttl_ms", 1_500))
        }
        until("the status line") { OverlayArea.rect != null }
        val r = OverlayArea.rect!!
        val screen = context.resources.displayMetrics
        assertTrue("in the lower half: ${r.toList()}", r[1] > screen.heightPixels / 2)
        assertTrue("in the middle: ${r.toList()}", abs((r[0] + r[2]) / 2 - screen.widthPixels / 2) <= 10)
        until("the status line to go") { OverlayArea.rect == null }

        // Read off the screen: an untranslated sentence does not show, its translation does.
        val final = JSONObject().put("t", "final").put("id", 1).put("o", "See you tomorrow").put("same", false)
        instrumentation.runOnMainSync { CaptionBus.emit(JSONObject(final.toString()).put("tr", JSONObject.NULL)) }
        SystemClock.sleep(700)
        assertNull("untranslated, not shown", OverlayArea.rect)
        instrumentation.runOnMainSync { CaptionBus.emit(JSONObject(final.toString()).put("tr", "Yarın görüşürüz")) }
        until("the translation") { OverlayArea.rect != null }
        assertNotNull(OverlayArea.rect)
        // A post's translation, longer: how it looks goes to files/overlay.png (CI shows it).
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
            File(context.filesDir, "overlay.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        instrumentation.runOnMainSync { overlay.hide() }
        assertNull(OverlayArea.rect)
        CaptionBus.last.clear()
    }
}
