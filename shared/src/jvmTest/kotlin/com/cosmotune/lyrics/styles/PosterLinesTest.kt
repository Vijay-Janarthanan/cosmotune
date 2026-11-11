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
}
