package com.cosmotune.lyrics.styles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosterLinesTest {
    @Test fun latinStaysTightAsTheBoardSetsIt() {
        assertTrue(PosterLines.tight("WE DREW A MAP ACROSS THE NIGHT"))
        assertTrue(PosterLines.tight("CAFÉ · ΑΘΗΝΑ · МОСКВА"))
        assertEquals(PosterLines.TIGHT, PosterLines.factor("HOLD THE LINE"))
    }

    @Test fun fallbackScriptsGetRoomForTheirTallerGlyphs() {
        for (text in listOf("艾德·希兰", "ワンオクロック", "사랑해", "SHAPE OF YOU 艾德")) {
            assertFalse(PosterLines.tight(text), text)
            assertEquals(PosterLines.FALLBACK, PosterLines.factor(text), text)
        }
    }

    @Test fun scriptsWithMarksAboveAndBelowGetTheMostRoom() {
        for (text in listOf("तुम ही हो", "இரவில் ஒரு விளக்கு", "ఓ ప్రియా", "สวัสดี")) {
            assertEquals(PosterLines.TALL, PosterLines.factor(text), text)
        }
        assertTrue(PosterLines.TALL > PosterLines.FALLBACK && PosterLines.FALLBACK > PosterLines.TIGHT)
    }

    @Test fun aSpacelessWordIsBrokenIntoBalancedRows() {
        for (word in listOf("夜里的小灯照亮回家的路", "夜の小さな灯りが道を照らす", "夜里的小灯照亮", "夜里的小灯照", "夜里的小灯照亮回家")) {
            val parts = PosterLines.chunks(word)
            assertEquals(word, parts.joinToString(""))
            assertTrue(parts.size >= 2, word)
            val sizes = parts.map { letters(it) }
            assertTrue(sizes.all { it in 3..PosterLines.ROW_MAX }, "$word $sizes")
            assertTrue(sizes.max() - sizes.min() <= 1, "$word $sizes")
        }
    }

    private fun letters(text: String): Int {
        val it = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        var n = 0
        while (it.next() != java.text.BreakIterator.DONE) n++
        return n
    }

    @Test fun shortAndSpacedWordsStayWhole() {
        for (word in listOf("夜里的灯", "HOLD", "LANTERN", "இரவில்", "रात")) assertEquals(listOf(word), PosterLines.chunks(word), word)
    }

    @Test fun thaiIsCutAtWordEnds() {
        val parts = PosterLines.chunks("คืนนี้มีแสงไฟเล็กๆ")
        assertEquals("คืนนี้มีแสงไฟเล็กๆ", parts.joinToString(""))
        assertTrue(parts.size >= 2)
        // A vowel written before its letter is never the last thing on a row.
        for (part in parts) assertFalse(part.last().code in 0x0E40..0x0E44, part)
    }

    @Test fun thaiMarksStayWithTheirLetters() {
        for (part in PosterLines.chunks("คืนนี้มีแสงไฟเล็กๆ")) {
            val first = part.first().code
            assertFalse(first in 0x0E31..0x0E3A || first in 0x0E47..0x0E4E, part)
        }
    }
}
