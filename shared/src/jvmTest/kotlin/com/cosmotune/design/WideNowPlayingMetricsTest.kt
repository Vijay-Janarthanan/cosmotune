package com.cosmotune.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wide Now Playing (`V2.deskNow`, `screens-v2.css:235-246`, a 1440 x 900 frame): the board's numbers, and how they give way
 * in a window that is not the board's size.
 */
class WideNowPlayingMetricsTest {

    private fun assertBox(x: Float, y: Float, w: Float, h: Float, box: WideNowPlayingMetrics.Box, what: String) {
        assertEquals(x, box.x, 0.001f, "$what x")
        assertEquals(y, box.y, 0.001f, "$what y")
        assertEquals(w, box.width, 0.001f, "$what width")
        assertEquals(h, box.height, 0.001f, "$what height")
    }

    // ---- the board --------------------------------------------------------------------------------------------------

    @Test
    fun theBoardsFrameGivesTheBoardsNumbers() {
        val plan = WideNowPlayingMetrics.plan(1440f, 900f)
        // The cover stays 56; the three text rows need 70 dp plus pixel-rounding clearance.
        assertBox(32f, 20f, 1376f, 72f, plan.header, "header")
        // .dn-lyrics: left 64, top 150, 640 wide; it ends 8 dp above the bar's top (800)
        assertBox(64f, 150f, 640f, 642f, plan.lyrics, "lyrics")
        // The wider 4:5 card retains the header and lyrics margins.
        assertBox(813f, 96f, 563f, 704f, plan.video, "video")
        // The responsive bar stays clear beneath the card.
        assertBox(32f, 800f, 886f, 80f, plan.bar, "bar")
        // the cover is a square of the card's width, centred in the card's height
        assertBox(813f, 166.5f, 563f, 563f, plan.cover, "cover")
    }

    @Test
    fun theNumbersAreTheBoardsOwn() {
        assertEquals(32f, WideNowPlayingMetrics.VIDEO_RADIUS_DP, 0f)
        assertEquals(56f, WideNowPlayingMetrics.HEADER_COVER_DP, 0f)
        assertEquals(14f, WideNowPlayingMetrics.HEADER_GAP_DP, 0f)
        // the lyrics: title-l, the line being sung 1.35 times as big, the lines already sung 0.82 (34 / 46 / 28 on the board)
        assertEquals(TypeScale.TITLE_L, WideNowPlayingMetrics.LYRICS_BASE_STEP)
        assertEquals(1.35f, WideNowPlayingMetrics.LYRICS_CURRENT_SCALE, 0f)
        assertEquals(0.82f, WideNowPlayingMetrics.LYRICS_PAST_SCALE, 0f)
        // the bar is the desktop player capsule: 80 dp tall
        assertEquals(PlayerBarMetrics.HEIGHT_DP, plan(1440f, 900f).bar.height, 0f)
    }

    private fun plan(w: Float, h: Float) = WideNowPlayingMetrics.plan(w, h)

    @Test
    fun theQualityRowFitsBelowTheTitleAndCaptionAtLargerTextSizes() {
        for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            val p = WideNowPlayingMetrics.plan(1280f, 800f, scale)
            val spaceBelowCaption = p.header.height - (21f + 17f) * scale
            assertTrue(spaceBelowCaption >= 32f, "the audio details retain their touch target at $scale")
            assertTrue(spaceBelowCaption >= 12f * scale + 4f, "the badge line and padding fit at $scale")
            assertTrue(p.header.bottom < p.video.y && p.header.bottom < p.lyrics.y)
            assertTrue(p.video.bottom <= p.bar.y && p.lyrics.bottom <= p.bar.y)
        }
    }

    // ---- a window that is not the board's --------------------------------------------------------------------------------

    @Test
    fun aTallerWindowDoesNotMakeTheCardTallerThanTheBoards() {
        val tall = plan(1440f, 1400f)
        assertEquals(704f, tall.video.height, 0f)
        assertEquals(563f, tall.video.width, 0f)
        // the bar stays at the foot and the lyrics get the height
        assertEquals(1400f - 20f - 80f, tall.bar.y, 0f)
        assertTrue(tall.lyrics.height > plan(1440f, 900f).lyrics.height)
    }

    @Test
    fun aShorterWindowShrinksTheCardKeepingFourByFiveAndTheBarClearOfIt() {
        val p = plan(1280f, 800f)
        // 800 - 96 - 100 = 604 tall, and as wide as a 4:5 card of that height
        assertEquals(604f, p.video.height, 0f)
        assertEquals(483f, p.video.width, 0f)
        assertEquals(1280f - 64f - 483f, p.video.x, 0f)
        assertTrue(p.video.bottom <= p.bar.y, "the card ends where the bar's row begins")
        assertEquals(96f, p.video.y, 0f)
    }

    @Test
    fun theBarKeepsTheBoardsMarginToTheCardAndKeepsTheRoomItNeeds() {
        // The wider video must not hide controls when a full bar still fits below both columns.
        val p = plan(1280f, 800f)
        assertEquals(886f, p.bar.width, 0f)
        assertEquals(PlayerBarMetrics.Tier.FULL, PlayerBarMetrics.plan(p.bar.width).tier)
        // the board's own frame still has room for the transport and scrubber
        assertTrue(plan(1440f, 900f).bar.width >= WideNowPlayingMetrics.BAR_MIN_WIDTH_DP)
        // the least window: the bar still has the scrubber (the compact plan's width)
        assertTrue(plan(900f, 600f).bar.width >= WideNowPlayingMetrics.BAR_MIN_WIDTH_DP)
        assertEquals(PlayerBarMetrics.Tier.MEDIUM, PlayerBarMetrics.plan(plan(900f, 600f).bar.width).tier)
    }

    @Test
    fun wideningThePortraitVideoDoesNotRemoveShuffleOrRepeatFromWideWindows() {
        for ((w, h) in listOf(840f to 600f, 1220f to 780f, 1440f to 900f, 1920f to 1080f)) {
            val p = plan(w, h)
            assertTrue(PlayerBarMetrics.plan(p.bar.width).shuffleRepeat, "$w x $h keeps shuffle/repeat")
            assertTrue(p.bar.right <= w - WideNowPlayingMetrics.BAR_LEFT_DP)
            assertTrue(p.video.bottom <= p.bar.y)
        }
    }

    @Test
    fun aNarrowWindowGivesTheLyricsTheirLeastAndShrinksTheCard() {
        val p = plan(840f, 900f)
        assertEquals(WideNowPlayingMetrics.LYRICS_MIN_WIDTH_DP, p.lyrics.width, 0f)
        assertTrue(p.video.width < 563f, "the card gave way: ${p.video}")
        assertTrue(abs(p.video.height / p.video.width - 5f / 4f) < 0.02f, "still four by five: ${p.video}")
    }

    @Test
    fun theCoverIsCentredInTheCardWhateverTheWindow() {
        for ((w, h) in listOf(840f to 600f, 1280f to 800f, 1440f to 900f, 1920f to 1080f)) {
            val p = plan(w, h)
            assertEquals(p.video.x, p.cover.x, 0f, "$w x $h")
            assertEquals(p.video.width, p.cover.width, 0f)
            assertEquals(p.cover.width, p.cover.height, 0f, "square")
            assertEquals(p.video.y + (p.video.height - p.cover.height) / 2f, p.cover.y, 0.001f, "$w x $h")
        }
    }

    // ---- nothing collides, nothing leaves the window ---------------------------------------------------------------------

    @Test
    fun noWideWindowHasAnythingOverlappingOrOutside() {
        for (w in 840..2800 step 40) for (h in 600..1700 step 25) {
            val p = plan(w.toFloat(), h.toFloat())
            val at = "at $w x $h"
            for ((name, box) in listOf("header" to p.header, "lyrics" to p.lyrics, "video" to p.video, "bar" to p.bar, "cover" to p.cover)) {
                assertTrue(box.x >= 0f && box.y >= 0f && box.width >= 0f && box.height >= 0f, "$name is not negative $at: $box")
                assertTrue(box.right <= w + 0.001f && box.bottom <= h + 0.001f, "$name is inside the window $at: $box")
            }
            assertTrue(p.header.bottom <= p.lyrics.y && p.header.bottom <= p.video.y, "the header is above the columns $at")
            assertTrue(p.lyrics.right + WideNowPlayingMetrics.COLUMN_GAP_DP <= p.video.x + 0.001f, "the lyrics clear the card $at")
            assertTrue(p.lyrics.bottom <= p.bar.y, "the lyrics end above the bar $at")
            assertTrue(p.video.bottom <= p.bar.y, "the card ends above the bar $at")
            assertTrue(p.lyrics.width >= WideNowPlayingMetrics.LYRICS_MIN_WIDTH_DP - 0.001f, "the lyrics keep their least $at")
            assertTrue(p.bar.width >= WideNowPlayingMetrics.BAR_MIN_WIDTH_DP - 0.001f, "the bar keeps its least $at")
            assertTrue(p.video.height >= 400f, "a card worth looking at $at: ${p.video}")
        }
    }

    @Test
    fun aWindowTooSmallForTheLayoutStillGivesFiniteNonNegativeBoxes() {
        for ((w, h) in listOf(0f to 0f, 100f to 100f, 840f to 300f, 300f to 900f, -5f to 40f, 1e6f to 1e6f)) {
            val p = plan(w, h)
            for (box in listOf(p.header, p.lyrics, p.video, p.bar, p.cover)) {
                assertTrue(box.x.isFinite() && box.y.isFinite() && box.width.isFinite() && box.height.isFinite(), "finite at $w x $h: $box")
                assertTrue(box.width >= 0f && box.height >= 0f, "not negative at $w x $h: $box")
            }
        }
    }
}
