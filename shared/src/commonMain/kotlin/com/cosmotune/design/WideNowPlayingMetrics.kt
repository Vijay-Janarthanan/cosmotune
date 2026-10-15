package com.cosmotune.design

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The wide Now Playing (`V2.deskNow`, `screens-v2.css:235-246`): the numbers of the board's 1440 x 900 frame, and [plan], which
 * says where the five pieces go in a window that is not the board's size. All dp. The Compose side places what [plan] says.
 *
 * The board: a header (cover, title over artist, the Lyrics | Queue tabs, close) 20 dp from the top and 32 dp from the sides; the
 * lyrics column 64 dp from the left, 150 dp from the top, 640 dp wide; the video card on the right, 64 dp from the edge, 96 dp
 * from the top, 396 x 704 (nine by sixteen), over the cover as a square of its width; and the player bar, 32 dp from the left,
 * 500 dp from the right, 20 dp from the bottom, 80 dp tall. The card ends 100 dp above the foot, which is where the bar's row
 * begins: the card and the bar never meet whatever the window.
 *
 * A window shorter than 900 dp shortens the card (and narrows it, to stay nine by sixteen); a window narrower than the board
 * needs narrows the card too, so the lyrics keep [LYRICS_MIN_WIDTH_DP] and the bar keeps [BAR_MIN_WIDTH_DP]. A taller or wider
 * window does not grow the card past the board's size; the lyrics and the bar take the room.
 */
object WideNowPlayingMetrics {
    const val HEADER_TOP_DP = 20f
    const val HEADER_SIDE_DP = 32f
    const val HEADER_GAP_DP = 14f
    const val HEADER_COVER_DP = 56f

    const val LYRICS_LEFT_DP = 64f
    const val LYRICS_TOP_DP = 150f
    const val LYRICS_WIDTH_DP = 640f

    const val VIDEO_RIGHT_DP = 64f
    const val VIDEO_TOP_DP = 96f
    const val VIDEO_WIDTH_DP = 396f
    const val VIDEO_HEIGHT_DP = 704f
    const val VIDEO_RADIUS_DP = 32f

    const val BAR_LEFT_DP = 32f
    const val BAR_BOTTOM_DP = 20f

    /** The lyrics: title-l, the line being sung 1.35 times as big (34 and 46 sp on the board), the lines already sung 0.82 (28). */
    const val LYRICS_BASE_STEP = TypeScale.TITLE_L
    const val LYRICS_CURRENT_SCALE = 1.35f
    const val LYRICS_PAST_SCALE = 0.82f

    /** The air between the lyrics and the video card (the board has 276 dp, with room to spare; this is the least). */
    const val COLUMN_GAP_DP = 40f

    /** The least the lyrics column is given; the card narrows before it takes this. */
    const val LYRICS_MIN_WIDTH_DP = 320f

    /** The air between the lyrics column's last line and the top of the bar. */
    const val LYRICS_BAR_AIR_DP = 8f

    /** The least the bar is given: the width of its compact plan, which still has the scrubber. */
    val BAR_MIN_WIDTH_DP: Float = PlayerBarMetrics.requiredDp(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.COMPACT))

    /** What the bar and the air above it take from the foot of the window. */
    private const val FOOT_DP = BAR_BOTTOM_DP + PlayerBarMetrics.HEIGHT_DP

    /** A rectangle: [x] and [y] from the window's top-left, in dp. */
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height
    }

    /**
     * Where the pieces go: the [header] across the top; the [lyrics] (or queue) column; the [video] card; the [cover], the card's
     * width square and centred in the card's height; the [bar].
     */
    data class Plan(val header: Box, val lyrics: Box, val video: Box, val cover: Box, val bar: Box)

    /** The plan for a window of [widthDp] by [heightDp]. Total: a window too small for the layout gets empty boxes, never negative ones. */
    fun plan(widthDp: Float, heightDp: Float): Plan {
        val w = max(widthDp, 0f)
        val h = max(heightDp, 0f)

        // The card: as tall as the board's, or as the window leaves above the foot; nine by sixteen.
        var videoH = min(VIDEO_HEIGHT_DP, h - VIDEO_TOP_DP - FOOT_DP)
        var videoW = videoH * VIDEO_WIDTH_DP / VIDEO_HEIGHT_DP
        // ... and no wider than leaves the lyrics column its least.
        val widest = w - VIDEO_RIGHT_DP - COLUMN_GAP_DP - LYRICS_MIN_WIDTH_DP - LYRICS_LEFT_DP
        if (videoW > widest) {
            videoW = widest
            videoH = videoW * VIDEO_HEIGHT_DP / VIDEO_WIDTH_DP
        }
        videoW = floor(max(videoW, 0f))
        videoH = floor(max(videoH, 0f))
        val videoX = max(w - VIDEO_RIGHT_DP - videoW, 0f)
        val video = Box(videoX, VIDEO_TOP_DP, videoW, videoH)
        val cover = Box(videoX, VIDEO_TOP_DP + (videoH - videoW) / 2f, videoW, videoW)

        val lyricsW = max(min(LYRICS_WIDTH_DP, videoX - COLUMN_GAP_DP - LYRICS_LEFT_DP), 0f)
        val lyricsH = max(h - FOOT_DP - LYRICS_BAR_AIR_DP - LYRICS_TOP_DP, 0f)
        val lyrics = Box(LYRICS_LEFT_DP, LYRICS_TOP_DP, lyricsW, lyricsH)

        // The bar keeps the board's distance to the card (500 dp from the right in the board's frame) unless that would leave it
        // less than its least; it may then run under the card's column, since the card ends above the bar's row.
        val barMin = min(BAR_MIN_WIDTH_DP, max(w - 2f * BAR_LEFT_DP, 0f))
        val barW = max(w - BAR_LEFT_DP - (VIDEO_RIGHT_DP + videoW + COLUMN_GAP_DP), barMin)
        val bar = Box(BAR_LEFT_DP, max(h - FOOT_DP, 0f), min(barW, max(w - 2f * BAR_LEFT_DP, 0f)), PlayerBarMetrics.HEIGHT_DP)

        val header = Box(HEADER_SIDE_DP, HEADER_TOP_DP, max(w - 2f * HEADER_SIDE_DP, 0f), HEADER_COVER_DP)
        return Plan(header, lyrics, video, cover, bar)
    }
}
