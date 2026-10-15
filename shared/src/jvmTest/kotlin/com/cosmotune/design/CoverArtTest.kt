package com.cosmotune.design

import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A cover asks the image cache for one of a few sizes, so a row and a card share entries (`Models.kt:260-263`). */
class CoverArtTest {
    @Test fun aSizeRoundsUpToTheNextRungOfTheLadder() {
        assertEquals(ROW_ART_PX, CoverArt.px(52f, 3f))        // 156 px
        assertEquals(ROW_ART_PX, CoverArt.px(40f, 2f))        // 80 px
        assertEquals(320, CoverArt.px(100f, 3f))              // 300 px
        assertEquals(CARD_ART_PX, CoverArt.px(166f, 2.75f))   // 456 px
        assertEquals(HEADER_ART_PX, CoverArt.px(200f, 3f))    // 600 px
        assertEquals(PLAYER_ART_PX, CoverArt.px(340f, 3f))    // 1020 px
    }

    @Test fun anExactRungStays() {
        assertEquals(ROW_ART_PX, CoverArt.px(80f, 2f))
        assertEquals(CARD_ART_PX, CoverArt.px(160f, 3f))
    }

    @Test fun nothingAskedForIsLargerThanThePlayersRung() {
        assertEquals(PLAYER_ART_PX, CoverArt.px(800f, 3f))
    }

    @Test fun theLadderIsAscendingAndStartsAtTheRowSize() {
        val ladder = CoverArt.LADDER
        assertEquals(ROW_ART_PX, ladder.first())
        assertTrue(ladder.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun anEmptyOrBrokenSizeGetsTheSmallestRung() {
        assertEquals(ROW_ART_PX, CoverArt.px(0f, 3f))
        assertEquals(ROW_ART_PX, CoverArt.px(-5f, 3f))
    }
}
