package dev.menzo.hammurabi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the captions show, on Android's own text classes. */
@RunWith(AndroidJUnit4::class)
class CaptionsTest {
    private fun final(id: Int, o: String, tr: String?, same: Boolean = false) =
        JSONObject().put("t", "final").put("id", id).put("o", o).put("tr", tr ?: JSONObject.NULL).put("same", same)

    @Test
    fun aTranslationShowsWithItsOriginal() {
        val c = Captions()
        c.apply(final(1, "Where are you going?", "Nereye gidiyorsun?"), now = 0L)
        assertEquals("Nereye gidiyorsun?\nWhere are you going?", c.text(40, 20, showOriginal = true, now = 0L).toString())
        assertEquals("Nereye gidiyorsun?", c.text(40, 20, showOriginal = false, now = 0L).toString())
    }

    @Test
    fun readOffTheScreenOnlyTranslationsShow() {
        val c = Captions()
        // Not translated yet; in the target language already; translated into the same words.
        c.apply(final(1, "Where are you going?", null), now = 0L)
        assertEquals("", c.text(40, 20, showOriginal = true, translatedOnly = true, now = 0L).toString())
        c.apply(final(2, "Bugün hava güzel", null, same = true), now = 0L)
        assertEquals("", c.text(40, 20, showOriginal = true, translatedOnly = true, now = 0L).toString())
        c.apply(final(3, "Bugun hava cok guzel", "Bugün hava çok güzel."), now = 0L)
        assertEquals("", c.text(40, 20, showOriginal = true, translatedOnly = true, now = 0L).toString())
        c.apply(final(4, "See you tomorrow", "Yarın görüşürüz"), now = 0L)
        assertEquals("Yarın görüşürüz", c.text(40, 20, showOriginal = true, translatedOnly = true, now = 0L).toString())
        // Listening, an untranslated sentence shows as heard.
        assertTrue(c.text(40, 20, showOriginal = true, now = 0L).toString().startsWith("Bugun hava cok guzel"))
    }

    @Test
    fun longTranslationsStayLongerToBeRead() {
        val c = Captions()
        c.apply(final(1, "Hi", "Selam"), now = 0L)
        assertEquals("Selam", c.text(40, 20, false, now = 3_900L).toString())
        assertEquals("", c.text(40, 20, false, now = 4_100L).toString())
        val long = "Bu yıl ne kadar çabuk geçti inanamıyorum, bu yolculuğun parçası olduğunuz için hepinize teşekkürler"
        c.apply(final(2, "I can't believe how fast this year went by...", long), now = 10_000L)
        // 1.5 s and 60 ms a letter.
        val hold = 1_500L + 60L * long.length
        assertEquals(long, c.text(40, 20, false, now = 10_000L + hold - 100).toString())
        assertEquals("", c.text(40, 20, false, now = 10_000L + hold + 100).toString())
    }

    @Test
    fun theBusKeepsTheLastStatusForAScreenThatOpensLater() {
        CaptionBus.last.clear()
        var heard = 0
        val listener: (JSONObject) -> Unit = { heard++ }
        CaptionBus.add(listener)
        CaptionBus.emit(JSONObject().put("t", "status").put("text", "x"))
        CaptionBus.emit(final(1, "a", null))
        CaptionBus.remove(listener)
        CaptionBus.emit(final(2, "b", null))
        assertEquals(2, heard)
        assertEquals(setOf("status"), CaptionBus.last.keys)
        CaptionBus.last.clear()
    }
}
