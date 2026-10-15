package com.cosmotune.design

import kotlin.math.max
import kotlin.math.min

/**
 * The landscape Now Playing (a window wider than tall and narrower than [NowPlayingLayout.WIDE_MIN_DP]): two panes, the media
 * on the left and the controls on the right. Pure numbers and the one decision made from them; the Compose side draws them.
 */
object LandscapeMetrics {
    /** The left pane is half the window. */
    const val MEDIA_FRACTION = 0.5f

    /** The room around the media inside its pane. */
    const val MEDIA_PADDING_DP = 12f

    /** The cover keeps the portrait cover's radius (28); the video is clipped to the same shape. */
    const val MEDIA_RADIUS_DP = 28f

    /** The play button is smaller than the portrait one (80): the right column is short. */
    const val PLAY_BUTTON_DP = 64f

    /** The title is one step down from the portrait display step (`display(DISPLAY_S)`). */
    const val TITLE_STEP = TypeScale.DISPLAY_S

    /** The side of the cover that fits a pane of [paneWidthDp] by [paneHeightDp], centred and square. Never negative. */
    fun coverSideDp(paneWidthDp: Float, paneHeightDp: Float): Float = max(0f, min(paneWidthDp, paneHeightDp))
}
