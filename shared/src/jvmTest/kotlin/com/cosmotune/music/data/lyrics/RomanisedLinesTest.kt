package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RomanisedLinesTest {

    private val original = listOf(
        LyricLine(1_000, "கண்ணே கலைமானே", words = listOf(LyricWord(1_000, 1_500, "கண்ணே"))),
        LyricLine(4_000, "♪"),
    )

    @Test
    fun theRomanisedTextTakesTheLinesPlaceAndKeepsItsTiming() {
        val shown = RomanisedLines.replace(original, listOf(LyricLine(1_000, "kanne kalaimaane"), LyricLine(4_000, "♪")))!!
        assertEquals("kanne kalaimaane", shown[0].text)
        assertEquals(1_000, shown[0].timeMs)
        assertEquals(emptyList(), shown[0].words)
        assertSame(original[0], shown[0].timingSource)
        assertSame(original[1], shown[1])
    }

    @Test
    fun linesThatDoNotLineUpAreLeftAlone() {
        assertNull(RomanisedLines.replace(original, listOf(LyricLine(1_000, "kanne"))))
        assertNull(RomanisedLines.replace(original, null))
    }
}
