package com.cosmotune.video.design

import com.cosmotune.design.BlockMetrics
import kotlin.math.floor
import kotlin.math.min

/**
 * Every number of Video mode's screens (spec 5.0, 5.4, 5.5, 5.7, 5.9, 5.15 and the chrome of 6.5), each with its source
 * or derivation. Dp and sp as floats, like [BlockMetrics]; milliseconds as `Long` where they are compared with clocks and
 * as `Int` where they are animation durations.
 */
object VideoMetrics {
    // ---- page (spec 5.0) ----
    /** Page margin: `--margin` (`tokens-v3bc.css`). */
    const val MARGIN_DP = 16f

    /** Gutter: `--gutter` (`tokens-v3bc.css`). */
    const val GUTTER_DP = 12f

    /** The desktop page margin: `.d2-main` padding. */
    const val DESKTOP_MARGIN_DP = 28f

    // ---- thumbnails (spec 5.0) ----
    /**
     * A thumbnail's corner radius: the cover rule keyed on the shorter side, so a 16:9 picture rounds like a square cover
     * of its height (`v2.js:64, 73`): 22 from 160 dp up, 20 from 140, 18 from 120, else 16. A vertical Shorts card passes
     * its width (its shorter side).
     */
    fun thumbRadiusDp(heightDp: Float): Float = BlockMetrics.cardRadiusDp(heightDp)

    /** The height of a 16:9 thumbnail [widthDp] wide (spec 5.0: "Thumbnail 16:9"). */
    fun thumbHeightDp(widthDp: Float): Float = widthDp * 9f / 16f

    // ---- feed card, phone (spec 5.0: `.mrow .ttl`, `.mrow` subtitle, `--s5`) ----
    /** The feed card's thumbnail is the full content width: the window less two [MARGIN_DP] (328 at 360 dp). */
    fun feedCardWidthDp(windowWidthDp: Float): Float = windowWidthDp - 2 * MARGIN_DP

    /** Space between the thumbnail and the meta row. */
    const val FEED_META_TOP_DP = 10f

    /** The channel avatar of a feed card. */
    const val FEED_AVATAR_DP = 36f

    /** Gap between the avatar and the text. */
    const val FEED_GAP_DP = 12f

    /** The title: 15.5 sp (`.mrow .ttl`), up to 2 lines. */
    const val FEED_TITLE_SP = 15.5f

    /** The title's weight (`.mrow .ttl`). */
    const val FEED_TITLE_WEIGHT = 650

    /** The title's line height factor (`.mrow .ttl`). */
    const val FEED_TITLE_LINE = 1.3f

    /** The meta line: 13 sp `textSecondary`, one line ("Channel · 1.2M views · 3 days ago"). */
    const val FEED_META_SP = 13f

    /** Space after a feed card (`--s5`). */
    const val FEED_AFTER_DP = 20f

    /** The plain "more" button of cards and rows. */
    const val MORE_BUTTON_DP = 40f

    // ---- shelf card (spec 5.0: `.small2` text sizes, `.rail2` spacing; two and a half cards show at 411 dp) ----
    /** Shelf card width. */
    const val SHELF_CARD_W_DP = 240f

    /** Shelf card height (16:9 of 240; radius 18). */
    const val SHELF_CARD_H_DP = 135f

    /** Shelf card title: 14 sp 650, up to 2 lines. */
    const val SHELF_TITLE_SP = 14f

    /** Shelf card meta. */
    const val SHELF_META_SP = 12.5f

    /** Gap between shelf cards. */
    const val SHELF_GAP_DP = 12f

    // ---- video row (spec 5.0: the `.mrow` rhythm with the cover replaced by a thumbnail) ----
    /** Row thumbnail width (radius 16). */
    const val ROW_THUMB_W_DP = 160f

    /** Row thumbnail height. */
    const val ROW_THUMB_H_DP = 90f

    /** The desktop row's thumbnail: 168 x 94.5, `BlockMetrics.LEAD_CARD_DP`. */
    const val DESKTOP_ROW_THUMB_W_DP = 168f

    /** A row's vertical padding (padding 8 16). */
    const val ROW_PAD_V_DP = 8f

    /** Row title: 15 sp 650, up to 2 lines. */
    const val ROW_TITLE_SP = 15f

    /** Row meta: 12.5 sp `textSecondary`, up to 2 lines. */
    const val ROW_META_SP = 12.5f

    /** A video row's height: its thumbnail and 8 dp above and below (106 for 90, 110.5 on the desktop). */
    fun rowHeightDp(thumbH: Float): Float = thumbH + 2 * ROW_PAD_V_DP

    // ---- badges (spec 5.0: radius 8 / 1.3, the superellipse rule of `02-design-formulas.md` section 3) ----
    /** The badge's distance from the thumbnail's bottom-right corner. */
    const val BADGE_INSET_DP = 8f

    /** Badge height. */
    const val BADGE_H_DP = 20f

    /** Badge horizontal padding (padding 0 6). */
    const val BADGE_PAD_H_DP = 6f

    /** Badge radius. */
    const val BADGE_RADIUS_DP = 6f

    /** Badge text: 12 sp 650 tabular. */
    const val BADGE_SP = 12f

    /** The duration badge's black behind white text (a media scrim, the documented exception). */
    const val BADGE_SCRIM_ALPHA = 0.72f

    // ---- watched bar (spec 5.0: the minimal scrubber's 3 dp line) ----
    /** The watched bar at the thumbnail's bottom edge. */
    const val WATCHED_BAR_DP = 3f

    /** The bar shows from 10 s watched. */
    const val WATCHED_MIN_MS = 10_000L

    /** A video counts as watched at 90 %. */
    const val WATCHED_DONE = 0.90f

    // ---- grid, 840 dp and wider (spec 5.0: `.dh-bento` gap 16; 280 keeps a title on two lines at 15.5 sp) ----
    /** A grid card's smallest width. */
    const val GRID_MIN_CARD_DP = 280f

    /** Gap between grid columns. */
    const val GRID_COL_GAP_DP = 16f

    /** Gap between grid rows. */
    const val GRID_ROW_GAP_DP = 28f

    /** The grid's columns for a page [widthDp] wide (margins included): clamp(floor((W - 2·28 + 16) / (280 + 16)), 2, 5). */
    fun gridColumns(widthDp: Float): Int =
        floor((widthDp - 2 * DESKTOP_MARGIN_DP + GRID_COL_GAP_DP) / (GRID_MIN_CARD_DP + GRID_COL_GAP_DP)).toInt().coerceIn(2, 5)

    /** A grid card's width for a page [widthDp] wide: the content width less the gaps, shared by [gridColumns] cards. */
    fun gridCardWidthDp(widthDp: Float): Float {
        val columns = gridColumns(widthDp)
        return (widthDp - 2 * DESKTOP_MARGIN_DP - (columns - 1) * GRID_COL_GAP_DP) / columns
    }

    // ---- the player on the watch page (spec 5.4) ----
    /**
     * The player's height for a window [windowW] x [windowH] and a video of [aspect] (width / height): `windowW / aspect`
     * for a landscape or square video (202.5 at 360 dp for 16:9); a vertical video is `min(windowW / aspect, 0.56 x windowH)`,
     * pillarboxed. An unknown aspect (0 or less) counts as 16:9.
     */
    fun playerHeightDp(windowW: Float, windowH: Float, aspect: Float): Float {
        val a = if (aspect > 0f) aspect else 16f / 9f
        val natural = windowW / a
        return if (a >= 1f) natural else min(natural, VERTICAL_PLAYER_MAX_FRACTION * windowH)
    }

    /** A vertical video's player is at most this share of the window's height (spec 5.4). */
    const val VERTICAL_PLAYER_MAX_FRACTION = 0.56f

    /** The wide watch page's side column (Up next, Chapters, Comments). */
    const val SIDE_COLUMN_DP = 400f

    /** Gap between the wide watch page's two columns. */
    const val COLUMN_GAP_DP = 24f

    /** The wide player's radius. */
    const val PLAYER_RADIUS_DP = 22f

    /** From this window width the screens use their wide layout (spec 5.0 grid, 5.4 wide). */
    const val WIDE_MIN_DP = 840f

    // ---- the chrome over the video (spec 6.5) ----
    /** Top scrim: the palette's `scrim` from 60 % to 0. */
    const val SCRIM_TOP_ALPHA = 0.60f

    /** Top scrim height in the page. */
    const val SCRIM_TOP_PAGE_DP = 72f

    /** Top scrim height in full screen. */
    const val SCRIM_TOP_FULL_DP = 120f

    /** Bottom scrim: from 0 to 72 %. */
    const val SCRIM_BOTTOM_ALPHA = 0.72f

    /** Bottom scrim height in the page (shorter than Now Playing's: the box is 202 dp tall on a phone). */
    const val SCRIM_BOTTOM_PAGE_DP = 96f

    /** Bottom scrim height in full screen. */
    const val SCRIM_BOTTOM_FULL_DP = 160f

    /** The centre's back and forward C icon buttons. */
    const val CENTRE_BUTTON_DP = 56f

    /** The centre's C play button (`components-v3bc.css`). */
    const val CENTRE_PLAY_DP = 64f

    /** Gap between the centre buttons in the page. */
    const val CENTRE_GAP_PAGE_DP = 28f

    /** Gap between the centre buttons in full screen. */
    const val CENTRE_GAP_FULL_DP = 40f

    /** Previous and next, only in full screen with a queue. */
    const val QUEUE_BUTTON_DP = 48f

    /** The top and bottom rows' C icon buttons (collapse, captions, settings, picture-in-picture, full screen). */
    const val CHROME_ICON_DP = 40f

    /** The time: 12.5 sp 600 tabular white 90 % ("1:21 / 4:08"). */
    const val TIME_SP = 12.5f

    /** The minimal scrubber's line. */
    const val SCRUB_LINE_DP = 3f

    /** The minimal scrubber's dot. */
    const val SCRUB_DOT_DP = 12f

    /** The line while dragging. */
    const val SCRUB_LINE_DRAG_DP = 5f

    /** The dot while dragging. */
    const val SCRUB_DOT_DRAG_DP = 16f

    /** In full screen the scrubber stands 24 dp from the sides. */
    const val SCRUB_FULL_SIDE_DP = 24f

    /** In full screen the scrubber stands 56 dp from the bottom. */
    const val SCRUB_FULL_BOTTOM_DP = 56f

    /** The gaps at chapter starts. */
    const val CHAPTER_GAP_DP = 2f

    /** The storyboard frame in the drag bubble (radius 12). */
    const val STORYBOARD_W_DP = 160f

    /** The storyboard frame's height. */
    const val STORYBOARD_H_DP = 90f

    /** The chrome hides 3,000 ms after the last input while playing (the `np-fullwindow` board). */
    const val HIDE_AFTER_MS = 3_000L

    /** The chrome's fade (none under reduced motion). */
    const val FADE_MS = 150

    /** The double-tap zones: the left and right 40 % of the box. */
    const val DOUBLE_TAP_ZONE = 0.40f

    /** The seek pill ("-10 s"): 36 dp, padding 0 14, 13 sp 650. */
    const val SEEK_PILL_H_DP = 36f

    /** The brightness and volume meter's width (radius 18, 24 dp from its edge). */
    const val METER_W_DP = 36f

    /** The meter's height. */
    const val METER_H_DP = 160f

    /** SponsorBlock's "Skip" pill: 40 dp, padding 0 18, 14 sp 650, 12 dp from the right. */
    const val SKIP_BUTTON_H_DP = 40f

    /** The "Skip" pill stands 48 dp above the bottom in the page. */
    const val SKIP_BOTTOM_PAGE_DP = 48f

    /** The "Skip" pill stands 96 dp above the bottom in full screen. */
    const val SKIP_BOTTOM_FULL_DP = 96f

    /** Subtitles on the phone's page (times the size setting). */
    const val SUB_SP_PHONE = 16f

    /** Subtitles in the phone's full screen. */
    const val SUB_SP_PHONE_FULL = 20f

    /** Subtitles on the desktop page. */
    const val SUB_SP_DESKTOP = 22f

    /** Subtitles in the desktop's full window. */
    const val SUB_SP_DESKTOP_FULL = 28f

    /** Subtitles stand 12 % of the box above its bottom. */
    const val SUB_BOTTOM_FRACTION = 0.12f

    /** The full window's control bar: 80 dp, radius 28, at most 720 dp wide (spec 5.4). */
    const val FULL_WINDOW_BAR_MAX_DP = 720f

    /** The full window's control bar stands 20 dp from the bottom (spec 5.4). */
    const val FULL_WINDOW_BAR_BOTTOM_DP = 20f

    // ---- Shorts (spec 5.0 and 5.15) ----
    /** A Shorts shelf card's width (132 x 235, radius 18; the vertical twin of the shelf card). */
    const val SHORTS_SHELF_W_DP = 132f

    /** A Shorts card's height for [widthDp]: 9:16. */
    fun shortsCardHeightDp(widthDp: Float): Float = widthDp * 16f / 9f

    /** A Shorts card's title: 13.5 sp 650, two lines, white inside the card at the bottom. */
    const val SHORTS_TITLE_SP = 13.5f

    /** A Shorts card's views. */
    const val SHORTS_VIEWS_SP = 12f

    /** The card's black scrim covers its lower 40 %. */
    const val SHORTS_SCRIM_FRACTION = 0.40f

    /** The card's scrim goes from 0 to 60 %. */
    const val SHORTS_SCRIM_ALPHA = 0.60f

    /** Gap between Shorts shelf cards (side padding 16). */
    const val SHORTS_SHELF_GAP_DP = 10f

    /** The phone's Shorts grid has 3 columns (106 x 188 at 360 dp, radius 16). */
    const val SHORTS_GRID_COLUMNS_PHONE = 3

    /** Gap of the phone's Shorts grid. */
    const val SHORTS_GRID_GAP_DP = 4f

    /** A wide Shorts grid card's smallest width. */
    const val SHORTS_GRID_MIN_CARD_DP = 180f

    /**
     * The Shorts grid's columns for a page [widthDp] wide: [SHORTS_GRID_COLUMNS_PHONE] below [WIDE_MIN_DP], else
     * clamp(floor((W - 56 + 12) / (180 + 12)), 3, 8).
     */
    fun shortsGridColumns(widthDp: Float): Int =
        if (widthDp < WIDE_MIN_DP) SHORTS_GRID_COLUMNS_PHONE
        else floor((widthDp - 2 * DESKTOP_MARGIN_DP + SHORTS_GRID_WIDE_GAP_DP) / (SHORTS_GRID_MIN_CARD_DP + SHORTS_GRID_WIDE_GAP_DP))
            .toInt().coerceIn(3, 8)

    /** Gap of the wide Shorts grid. */
    const val SHORTS_GRID_WIDE_GAP_DP = 12f

    /** The pager's top scrim: black from 40 % to 0 over the top 120 dp. */
    const val PAGER_TOP_SCRIM_DP = 120f

    /** The pager's top scrim's darkest alpha. */
    const val PAGER_TOP_SCRIM_ALPHA = 0.40f

    /** The pager's bottom scrim: from 0 to 55 % over the bottom 260 dp. */
    const val PAGER_BOTTOM_SCRIM_DP = 260f

    /** The pager's bottom scrim's darkest alpha. */
    const val PAGER_BOTTOM_SCRIM_ALPHA = 0.55f

    /** The pager's top row buttons (back, mute): C icon buttons 40. */
    const val PAGER_TOP_BUTTON_DP = 40f

    /** "Shorts" in the pager's top row: 17 sp 700. */
    const val PAGER_TITLE_BAR_SP = 17f

    /** The right rail's buttons (like, comments, share, more): C icon buttons 48. */
    const val PAGER_RAIL_BUTTON_DP = 48f

    /** Gap between the rail's buttons. */
    const val PAGER_RAIL_GAP_DP = 18f

    /** The rail stands 12 dp from the right edge. */
    const val PAGER_RAIL_END_DP = 12f

    /** The rail's bottom stands 96 dp above the bottom inset. */
    const val PAGER_RAIL_BOTTOM_DP = 96f

    /** The bottom block starts 16 dp from the left. */
    const val PAGER_BLOCK_START_DP = 16f

    /** The bottom block ends 84 dp from the right (clear of the rail). */
    const val PAGER_BLOCK_END_DP = 84f

    /** The bottom block stands 28 dp above the bottom inset. */
    const val PAGER_BLOCK_BOTTOM_DP = 28f

    /** The channel row's avatar. */
    const val PAGER_AVATAR_DP = 32f

    /** The channel row's C chip ("Subscribe" or "Subscribed"). */
    const val PAGER_CHIP_DP = 30f

    /** The Short's title: 14.5 sp 500, two lines (five when tapped). */
    const val PAGER_SHORT_TITLE_SP = 14.5f

    /** The progress line: 2 dp, played white 90 %, rest white 30 %, no dot. */
    const val PAGER_PROGRESS_DP = 2f

    /** The progress line while dragging. */
    const val PAGER_PROGRESS_DRAG_DP = 4f

    /** The C play glyph that fades in at the centre when pausing. */
    const val PAGER_PAUSE_GLYPH_DP = 64f

    /** How long the pause glyph shows. */
    const val PAGER_PAUSE_GLYPH_MS = 600

    /** The heart burst of a double tap (`HeartFill` in `accent`, scale 0.6 to 1.2 to 1 and fade). */
    const val HEART_BURST_DP = 96f

    /** The heart burst's duration (reduced motion: a 150 ms fade). */
    const val HEART_BURST_MS = 600

    /** The desktop Shorts column stands 16 dp below the page's top and above its bottom (radius 22). */
    const val DESKTOP_SHORTS_VMARGIN_DP = 16f

    /** More Shorts are loaded three pages before the end. */
    const val SHORTS_LOAD_MORE_BEFORE = 3

    // ---- channel (spec 5.5) ----
    /** The phone's channel banner: `0.274 x W` tall (the banner safe area is about 3.65:1); 98.6 at 360 dp. */
    fun channelBannerHeightDp(widthDp: Float): Float = 0.274f * widthDp

    /** The channel avatar circle on the phone. */
    const val CHANNEL_AVATAR_DP = 72f

    /** The channel avatar on the desktop. */
    const val CHANNEL_AVATAR_DESKTOP_DP = 96f

    /** The desktop banner's height (radius 22, inside the page margin). */
    const val DESKTOP_BANNER_DP = 180f

    // ---- the channel strip of Subscriptions (spec 5.7) ----
    /** The strip's avatar circles. */
    const val STRIP_AVATAR_DP = 56f

    /** A strip item's width (the avatar over a 12 sp name; gap 12). */
    const val STRIP_ITEM_W_DP = 72f

    // ---- comments (spec 5.9) ----
    /** A comment's avatar. */
    const val COMMENT_AVATAR_DP = 32f

    /** A comment's text: 14.5 sp, line 1.45. */
    const val COMMENT_TEXT_SP = 14.5f

    /** A comment shows 6 lines, then "Read more". */
    const val COMMENT_MAX_LINES = 6
}
