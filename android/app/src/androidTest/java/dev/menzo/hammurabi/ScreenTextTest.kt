package dev.menzo.hammurabi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How the screen's lines become paragraphs to translate, and which are worth it. */
@RunWith(AndroidJUnit4::class)
class ScreenTextTest {
    @Test
    fun linesBecomeOneParagraph() {
        assertEquals(
            "I can't believe how fast this year went by.",
            ScreenText.paragraph(listOf("I can't believe how", "fast this year went by.")),
        )
        // A word broken at the line's end is whole again; a compound keeps its hyphen.
        assertEquals("a translation app", ScreenText.paragraph(listOf("a transla-", "tion app")))
        assertEquals("a well-Known name", ScreenText.paragraph(listOf("a well-", "Known name")))
        // Chinese and Japanese lines join without a space.
        assertEquals("今天天气很好我们去公园", ScreenText.paragraph(listOf("今天天气很好", "我们去公园")))
        assertEquals("one two", ScreenText.paragraph(listOf("  one ", "", "two")))
    }

    @Test
    fun wordsAreWorthTranslatingNumbersAreNot() {
        assertTrue(ScreenText.worthTranslating("Where did you put the keys?"))
        assertTrue(ScreenText.worthTranslating("Like"))
        assertFalse(ScreenText.worthTranslating("12:45"))
        assertFalse(ScreenText.worthTranslating("1.2K"))
        assertFalse(ScreenText.worthTranslating("♥ 234 · 56"))
        assertFalse(ScreenText.worthTranslating("x"))
    }

    @Test
    fun namesAndButtonsAreTooShortToTellTheirLanguage() {
        assertTrue(ScreenText.isShort("John Carter"))
        assertTrue(ScreenText.isShort("Follow"))
        assertTrue(ScreenText.isShort("Wed, Nov 18"))
        assertFalse(ScreenText.isShort("Where did you put the keys?"))
        assertFalse(ScreenText.isShort("Bugün arkadaşlarla harika bir akşam geçirdik."))
    }

    @Test
    fun aTranslationGivingBackTheOriginalsWordsIsNone() {
        assertTrue(ScreenText.onlyRepeats("Carter", "John Carter"))
        assertTrue(ScreenText.onlyRepeats("John Carter", "John Carter"))
        assertTrue(ScreenText.onlyRepeats("", "Follow"))
        assertFalse(ScreenText.onlyRepeats("Takip et", "Follow"))
        assertFalse(ScreenText.onlyRepeats("Anahtarları nereye koydun?", "Where did you put the keys?"))
    }

    @Test
    fun theScreensLanguageIsWhatMostOfItIsIn() {
        val langs = listOf("en" to 120, "und" to 400, "tr" to 40, "en" to 30)
        assertEquals("en", ScreenText.dominant(langs, "tr"))
        // Mostly in the target language already: short blocks are left as they are.
        assertNull(ScreenText.dominant(listOf("tr" to 300, "en" to 20), "tr"))
        assertNull(ScreenText.dominant(listOf("und" to 10), "tr"))
    }
}
