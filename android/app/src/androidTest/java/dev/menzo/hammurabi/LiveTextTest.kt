package dev.menzo.hammurabi

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** How live translation puts subtitle lines together, tells a new line from the same one, and reads capitals. */
@RunWith(AndroidJUnit4::class)
class LiveTextTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = LiveText.Line(text, Rect(l, t, r, b))

    @Test
    fun aSubtitlesLinesAreOneTextOtherTextIsApart() {
        val groups = LiveText.groups(
            listOf(
                // A centred two-line subtitle, the second line shorter.
                line("I can't believe you came", 200, 1700, 880, 1750),
                line("all this way.", 360, 1758, 720, 1808),
                // A channel's name far above it.
                line("Movie Channel", 40, 120, 300, 160),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals("Movie Channel", groups[0].text)
        assertEquals("I can't believe you came all this way.", groups[1].text)
        assertEquals(200, groups[1].box.left)
        assertEquals(1808, groups[1].box.bottom)
    }

    @Test
    fun aDialogueBoxsLeftAlignedLinesAreOneText() {
        val groups = LiveText.groups(
            listOf(
                line("The castle gates are closed.", 60, 1500, 900, 1550),
                line("We must find another way in.", 60, 1556, 860, 1606),
            ),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun theSameLineReadAgainIsTheSameAWrittenOutOneIsNot() {
        assertTrue(LiveText.same("Where did you put the keys?", "Where did you put the keys?"))
        // Read a little differently over a moving picture.
        assertTrue(LiveText.same("Where did you put the keys?", "Where did you put the keys ?"))
        assertTrue(LiveText.same("Where did you put the keys?", "Where dld you put the keys?"))
        // Still being written out.
        assertFalse(LiveText.same("We have to", "We have to leave before dawn."))
        assertFalse(LiveText.same("The castle gates are closed. We must find", "The castle gates are closed. We must find ano"))
        assertFalse(LiveText.same("", ""))
        assertTrue(LiveText.changed("We have to leave before dawn.", "Where did you put the keys?"))
        assertTrue(LiveText.changed("The castle gates are closed. We must", "The castle gates are"))
        assertFalse(LiveText.changed("Where did you put the keys ?", "Where did you put the keys?"))
        // The last short word written out after the rest was translated.
        assertTrue(LiveText.changed("WE MUST FIND ANOTHER WAY IN.", "WE MUST FIND ANOTHER WAY"))
        assertFalse(LiveText.changed("WE MUST FIND ANOTHER WAY IN.", "WE MUST FIND ANOTHER WAY IN"))
    }

    @Test
    fun capitalsAreWrittenAsSentences() {
        assertEquals(
            "The castle gates are closed. We must find another way in.",
            LiveText.sentenceCase("THE CASTLE GATES ARE CLOSED. WE MUST FIND ANOTHER WAY IN.", Locale.ENGLISH),
        )
        assertEquals("Where am I? I don't know.", LiveText.sentenceCase("WHERE AM I? I DON'T KNOW.", Locale.ENGLISH))
        // Already a sentence, or an abbreviation: as it is.
        assertEquals("Call the FBI now.", LiveText.sentenceCase("Call the FBI now.", Locale.ENGLISH))
        assertEquals("OK", LiveText.sentenceCase("OK", Locale.ENGLISH))
    }
}
