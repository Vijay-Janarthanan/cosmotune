package com.cosmotune.video.design

import com.cosmotune.design.BlockMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

/** Video mode's numbers: the worked examples of spec 5.0 and the values of 5.0, 5.15 and 6.5. */
class VideoMetricsTest {

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test
    fun aFeedCardAt360Is328By184AndAHalfWithRadius22() {
        val width = VideoMetrics.feedCardWidthDp(360f)
        assertEquals(328f, width)
        assertEquals(184.5f, VideoMetrics.thumbHeightDp(width))
        assertEquals(22f, VideoMetrics.thumbRadiusDp(VideoMetrics.thumbHeightDp(width)))
    }

    @Test
    fun theThumbnailRadiusIsTheCoverRuleKeyedOnTheHeight() {
        for (h in listOf(0f, 90f, 119.9f, 120f, 135f, 140f, 159.9f, 160f, 184.5f, 400f)) {
            assertEquals(BlockMetrics.cardRadiusDp(h), VideoMetrics.thumbRadiusDp(h), "height $h")
        }
    }

    @Test
    fun aVideoRowIs160By90WithRadius16And106Tall() {
        assertEquals(160f, VideoMetrics.ROW_THUMB_W_DP)
        assertEquals(90f, VideoMetrics.ROW_THUMB_H_DP)
        assertEquals(90f, VideoMetrics.thumbHeightDp(VideoMetrics.ROW_THUMB_W_DP))
        assertEquals(16f, VideoMetrics.thumbRadiusDp(VideoMetrics.ROW_THUMB_H_DP))
        assertEquals(106f, VideoMetrics.rowHeightDp(VideoMetrics.ROW_THUMB_H_DP))
    }

    @Test
    fun theDesktopRowIs168By94AndAHalfAnd110AndAHalfTall() {
        assertEquals(BlockMetrics.LEAD_CARD_DP, VideoMetrics.DESKTOP_ROW_THUMB_W_DP)
        val h = VideoMetrics.thumbHeightDp(VideoMetrics.DESKTOP_ROW_THUMB_W_DP)
        assertEquals(94.5f, h)
        assertEquals(110.5f, VideoMetrics.rowHeightDp(h))
    }

    @Test
    fun aShelfCardIs240By135WithRadius18() {
        assertEquals(240f, VideoMetrics.SHELF_CARD_W_DP)
        assertEquals(135f, VideoMetrics.SHELF_CARD_H_DP)
        assertEquals(VideoMetrics.SHELF_CARD_H_DP, VideoMetrics.thumbHeightDp(VideoMetrics.SHELF_CARD_W_DP))
        assertEquals(18f, VideoMetrics.thumbRadiusDp(VideoMetrics.SHELF_CARD_H_DP))
    }

    @Test
    fun theGridHasTwoToFiveColumns() {
        assertEquals(3, VideoMetrics.gridColumns(1060f))
        assertEquals(2, VideoMetrics.gridColumns(700f))
        assertEquals(5, VideoMetrics.gridColumns(2200f))
        assertEquals(2, VideoMetrics.gridColumns(0f))
        // 1060 - 56 = 1004 of content, two 16 dp gaps, three cards
        assertEquals(324f, VideoMetrics.gridCardWidthDp(1060f))
    }

    @Test
    fun thePlayerIs16By9OrAVerticalVideoCappedAt56PercentOfTheWindow() {
        near(202.5f, VideoMetrics.playerHeightDp(360f, 800f, 16f / 9f))
        near(448f, VideoMetrics.playerHeightDp(360f, 800f, 9f / 16f))   // min(640, 0.56 x 800)
        near(360f, VideoMetrics.playerHeightDp(360f, 800f, 1f))
        near(270f, VideoMetrics.playerHeightDp(360f, 2000f, 4f / 3f))  // a landscape video is never capped
    }

    @Test
    fun theChannelBannerIs98AndAHalfTallAt360() {
        near(98.64f, VideoMetrics.channelBannerHeightDp(360f))
    }

    @Test
    fun aShortsShelfCardIs132By234AndTwoThirdsWithRadius18() {
        assertEquals(132f, VideoMetrics.SHORTS_SHELF_W_DP)
        near(234.67f, VideoMetrics.shortsCardHeightDp(VideoMetrics.SHORTS_SHELF_W_DP))
        assertEquals(18f, VideoMetrics.thumbRadiusDp(VideoMetrics.SHORTS_SHELF_W_DP))   // keyed on the width, its shorter side
    }

    @Test
    fun theShortsGridHasThreeColumnsOnAPhoneAndUpToEightWhenWide() {
        assertEquals(3, VideoMetrics.shortsGridColumns(360f))
        assertEquals(3, VideoMetrics.shortsGridColumns(839f))
        assertEquals(5, VideoMetrics.shortsGridColumns(1060f))
        assertEquals(8, VideoMetrics.shortsGridColumns(3000f))
        assertEquals(VideoMetrics.SHORTS_GRID_COLUMNS_PHONE, VideoMetrics.shortsGridColumns(411f))
    }

    @Test
    fun thePageAndCardNumbersAreThoseOfSpec50() {
        assertEquals(16f, VideoMetrics.MARGIN_DP)
        assertEquals(12f, VideoMetrics.GUTTER_DP)
        assertEquals(28f, VideoMetrics.DESKTOP_MARGIN_DP)
        assertEquals(10f, VideoMetrics.FEED_META_TOP_DP)
        assertEquals(36f, VideoMetrics.FEED_AVATAR_DP)
        assertEquals(12f, VideoMetrics.FEED_GAP_DP)
        assertEquals(15.5f, VideoMetrics.FEED_TITLE_SP)
        assertEquals(650, VideoMetrics.FEED_TITLE_WEIGHT)
        assertEquals(1.3f, VideoMetrics.FEED_TITLE_LINE)
        assertEquals(13f, VideoMetrics.FEED_META_SP)
        assertEquals(20f, VideoMetrics.FEED_AFTER_DP)
        assertEquals(40f, VideoMetrics.MORE_BUTTON_DP)
        assertEquals(14f, VideoMetrics.SHELF_TITLE_SP)
        assertEquals(12.5f, VideoMetrics.SHELF_META_SP)
        assertEquals(12f, VideoMetrics.SHELF_GAP_DP)
        assertEquals(8f, VideoMetrics.ROW_PAD_V_DP)
        assertEquals(15f, VideoMetrics.ROW_TITLE_SP)
        assertEquals(12.5f, VideoMetrics.ROW_META_SP)
        assertEquals(8f, VideoMetrics.BADGE_INSET_DP)
        assertEquals(20f, VideoMetrics.BADGE_H_DP)
        assertEquals(6f, VideoMetrics.BADGE_PAD_H_DP)
        assertEquals(6f, VideoMetrics.BADGE_RADIUS_DP)
        assertEquals(12f, VideoMetrics.BADGE_SP)
        assertEquals(0.72f, VideoMetrics.BADGE_SCRIM_ALPHA)
        assertEquals(3f, VideoMetrics.WATCHED_BAR_DP)
        assertEquals(10_000L, VideoMetrics.WATCHED_MIN_MS)
        assertEquals(0.90f, VideoMetrics.WATCHED_DONE)
        assertEquals(280f, VideoMetrics.GRID_MIN_CARD_DP)
        assertEquals(16f, VideoMetrics.GRID_COL_GAP_DP)
        assertEquals(28f, VideoMetrics.GRID_ROW_GAP_DP)
        assertEquals(56f, VideoMetrics.STRIP_AVATAR_DP)
        assertEquals(72f, VideoMetrics.STRIP_ITEM_W_DP)
        assertEquals(32f, VideoMetrics.COMMENT_AVATAR_DP)
        assertEquals(14.5f, VideoMetrics.COMMENT_TEXT_SP)
        assertEquals(6, VideoMetrics.COMMENT_MAX_LINES)
        assertEquals(72f, VideoMetrics.CHANNEL_AVATAR_DP)
        assertEquals(96f, VideoMetrics.CHANNEL_AVATAR_DESKTOP_DP)
        assertEquals(180f, VideoMetrics.DESKTOP_BANNER_DP)
    }

    @Test
    fun theWatchPageAndChromeNumbersAreThoseOfSpec54And65() {
        assertEquals(400f, VideoMetrics.SIDE_COLUMN_DP)
        assertEquals(24f, VideoMetrics.COLUMN_GAP_DP)
        assertEquals(22f, VideoMetrics.PLAYER_RADIUS_DP)
        assertEquals(840f, VideoMetrics.WIDE_MIN_DP)
        assertEquals(0.60f, VideoMetrics.SCRIM_TOP_ALPHA)
        assertEquals(72f, VideoMetrics.SCRIM_TOP_PAGE_DP)
        assertEquals(120f, VideoMetrics.SCRIM_TOP_FULL_DP)
        assertEquals(0.72f, VideoMetrics.SCRIM_BOTTOM_ALPHA)
        assertEquals(96f, VideoMetrics.SCRIM_BOTTOM_PAGE_DP)
        assertEquals(160f, VideoMetrics.SCRIM_BOTTOM_FULL_DP)
        assertEquals(56f, VideoMetrics.CENTRE_BUTTON_DP)
        assertEquals(64f, VideoMetrics.CENTRE_PLAY_DP)
        assertEquals(28f, VideoMetrics.CENTRE_GAP_PAGE_DP)
        assertEquals(40f, VideoMetrics.CENTRE_GAP_FULL_DP)
        assertEquals(48f, VideoMetrics.QUEUE_BUTTON_DP)
        assertEquals(40f, VideoMetrics.CHROME_ICON_DP)
        assertEquals(12.5f, VideoMetrics.TIME_SP)
        assertEquals(3f, VideoMetrics.SCRUB_LINE_DP)
        assertEquals(12f, VideoMetrics.SCRUB_DOT_DP)
        assertEquals(5f, VideoMetrics.SCRUB_LINE_DRAG_DP)
        assertEquals(16f, VideoMetrics.SCRUB_DOT_DRAG_DP)
        assertEquals(24f, VideoMetrics.SCRUB_FULL_SIDE_DP)
        assertEquals(56f, VideoMetrics.SCRUB_FULL_BOTTOM_DP)
        assertEquals(2f, VideoMetrics.CHAPTER_GAP_DP)
        assertEquals(160f, VideoMetrics.STORYBOARD_W_DP)
        assertEquals(90f, VideoMetrics.STORYBOARD_H_DP)
        assertEquals(3_000L, VideoMetrics.HIDE_AFTER_MS)
        assertEquals(150, VideoMetrics.FADE_MS)
        assertEquals(0.40f, VideoMetrics.DOUBLE_TAP_ZONE)
        assertEquals(36f, VideoMetrics.SEEK_PILL_H_DP)
        assertEquals(36f, VideoMetrics.METER_W_DP)
        assertEquals(160f, VideoMetrics.METER_H_DP)
        assertEquals(40f, VideoMetrics.SKIP_BUTTON_H_DP)
        assertEquals(48f, VideoMetrics.SKIP_BOTTOM_PAGE_DP)
        assertEquals(96f, VideoMetrics.SKIP_BOTTOM_FULL_DP)
        assertEquals(16f, VideoMetrics.SUB_SP_PHONE)
        assertEquals(20f, VideoMetrics.SUB_SP_PHONE_FULL)
        assertEquals(22f, VideoMetrics.SUB_SP_DESKTOP)
        assertEquals(28f, VideoMetrics.SUB_SP_DESKTOP_FULL)
        assertEquals(0.12f, VideoMetrics.SUB_BOTTOM_FRACTION)
        assertEquals(720f, VideoMetrics.FULL_WINDOW_BAR_MAX_DP)
        assertEquals(20f, VideoMetrics.FULL_WINDOW_BAR_BOTTOM_DP)
    }

    @Test
    fun theShortsNumbersAreThoseOfSpec50And515() {
        assertEquals(13.5f, VideoMetrics.SHORTS_TITLE_SP)
        assertEquals(12f, VideoMetrics.SHORTS_VIEWS_SP)
        assertEquals(0.40f, VideoMetrics.SHORTS_SCRIM_FRACTION)
        assertEquals(0.60f, VideoMetrics.SHORTS_SCRIM_ALPHA)
        assertEquals(10f, VideoMetrics.SHORTS_SHELF_GAP_DP)
        assertEquals(4f, VideoMetrics.SHORTS_GRID_GAP_DP)
        assertEquals(12f, VideoMetrics.SHORTS_GRID_WIDE_GAP_DP)
        assertEquals(120f, VideoMetrics.PAGER_TOP_SCRIM_DP)
        assertEquals(0.40f, VideoMetrics.PAGER_TOP_SCRIM_ALPHA)
        assertEquals(260f, VideoMetrics.PAGER_BOTTOM_SCRIM_DP)
        assertEquals(0.55f, VideoMetrics.PAGER_BOTTOM_SCRIM_ALPHA)
        assertEquals(40f, VideoMetrics.PAGER_TOP_BUTTON_DP)
        assertEquals(17f, VideoMetrics.PAGER_TITLE_BAR_SP)
        assertEquals(48f, VideoMetrics.PAGER_RAIL_BUTTON_DP)
        assertEquals(18f, VideoMetrics.PAGER_RAIL_GAP_DP)
        assertEquals(12f, VideoMetrics.PAGER_RAIL_END_DP)
        assertEquals(96f, VideoMetrics.PAGER_RAIL_BOTTOM_DP)
        assertEquals(16f, VideoMetrics.PAGER_BLOCK_START_DP)
        assertEquals(84f, VideoMetrics.PAGER_BLOCK_END_DP)
        assertEquals(28f, VideoMetrics.PAGER_BLOCK_BOTTOM_DP)
        assertEquals(32f, VideoMetrics.PAGER_AVATAR_DP)
        assertEquals(30f, VideoMetrics.PAGER_CHIP_DP)
        assertEquals(14.5f, VideoMetrics.PAGER_SHORT_TITLE_SP)
        assertEquals(2f, VideoMetrics.PAGER_PROGRESS_DP)
        assertEquals(4f, VideoMetrics.PAGER_PROGRESS_DRAG_DP)
        assertEquals(64f, VideoMetrics.PAGER_PAUSE_GLYPH_DP)
        assertEquals(600, VideoMetrics.PAGER_PAUSE_GLYPH_MS)
        assertEquals(96f, VideoMetrics.HEART_BURST_DP)
        assertEquals(600, VideoMetrics.HEART_BURST_MS)
        assertEquals(16f, VideoMetrics.DESKTOP_SHORTS_VMARGIN_DP)
        assertEquals(3, VideoMetrics.SHORTS_LOAD_MORE_BEFORE)
    }
}
