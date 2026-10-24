package com.cosmotune.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BC-33: which way a lyric line reads, from its own text, with Hebrew and Arabic fixtures; and the side it is written from. */
class LyricTextDirectionTest {
    @Test fun hebrewAndArabicReadRightToLeft() {
        assertTrue(LyricTextDirection.isRtl("שלום עולם"))
        assertTrue(LyricTextDirection.isRtl("مرحبا بالعالم"))
        assertTrue(LyricTextDirection.isRtl("سلام دنیا"), "Persian, in Arabic letters")
        assertTrue(LyricTextDirection.isRtl("\uFB2A"), "a Hebrew presentation form decides alone")
    }

    @Test fun latinCyrillicAndNeutralTextReadLeftToRight() {
        assertFalse(LyricTextDirection.isRtl("Hello world"))
        assertFalse(LyricTextDirection.isRtl("Привет мир"))
        assertFalse(LyricTextDirection.isRtl("123 456 ... !"), "digits and punctuation are neutral")
        assertFalse(LyricTextDirection.isRtl(""))
    }

    @Test fun aMixedLineGoesWithTheScriptThatHasMoreLetters() {
        assertTrue(LyricTextDirection.isRtl("Ya habibi حبيبي يا عمري"), "mostly Arabic, with a Latin tag")
        assertFalse(LyricTextDirection.isRtl("I love you so much, שלום"), "mostly English, with a Hebrew word")
        assertTrue(LyricTextDirection.isRtl("שלום 2024"), "numbers do not outvote the letters")
    }

    @Test fun theSideIsTheTextsAndADuetsSecondVoiceTakesTheFarEnd() {
        assertFalse(LyricTextDirection.fromRight(textRtl = false, onEnd = false), "English: from the left")
        assertTrue(LyricTextDirection.fromRight(textRtl = true, onEnd = false), "Hebrew: from the right")
        assertTrue(LyricTextDirection.fromRight(textRtl = false, onEnd = true), "an English second voice: on the right")
        assertFalse(LyricTextDirection.fromRight(textRtl = true, onEnd = true), "a Hebrew second voice: on the left")
    }
}
