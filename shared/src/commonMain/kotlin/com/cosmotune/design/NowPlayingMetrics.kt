package com.cosmotune.design

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The new Now Playing, portrait: its numbers (`screens-v3.css:131-161`, `V3.nowPlaying`) and the small decisions made from
 * them. Pure, so they are tested on the JVM; the Compose side only draws what these say. All sizes are dp.
 */
object NowPlayingMetrics {
    /** The cover without a video: 320 dp square, 112 dp from the top of the window (`.np2.no-video .np2-art`). */
    const val COVER_DP = 320f
    const val COVER_TOP_DP = 112f

    /** The cover never goes below this, whatever the window; a window that short is a stub until the landscape layout. */
    const val COVER_MIN_DP = 96f

    /** The least air between the cover's bottom edge and the title stack. */
    const val COVER_GAP_DP = 16f

    /** The page margin the cover keeps on each side in a window narrower than 360 dp (the stack's own side padding). */
    const val SIDE_PADDING_DP = 20f

    /** The cover as it lands beside the artist (`cv` 32 x 32, radius 9) and the gap after it. */
    const val CHIP_DP = 32f
    const val CHIP_GAP_DP = 8f
    const val CHIP_SLOT_DP = CHIP_DP + CHIP_GAP_DP

    /** The cover hands over to a real 32 dp picture in the last part of its trip (see [chipShare]). */
    private const val HANDOVER_FROM = 0.7f

    /** The title arrives through [TITLE_STEPS] + 1 instances of the display face, light and wide to heavy and narrow. */
    const val TITLE_STEPS = 5
    private const val TRACKING_START_EM = 0.01f
    private const val TRACKING_END_EM = -0.022f

    /** The glow at the foot of the video: a 200 dp band, `radial-gradient(70% 90% at 50% 115%, glow 62%, glow 24% 50%, transparent 78%)`. */
    const val GLOW_BAND_DP = 200f
    const val GLOW_ALPHA_INNER = 0.62f
    const val GLOW_ALPHA_MID = 0.24f
    const val GLOW_MID_AT = 0.5f
    const val GLOW_END_AT = 0.78f
    const val GLOW_RX_OF_WIDTH = 0.70f
    const val GLOW_RY_OF_BAND = 0.90f
    const val GLOW_CY_OF_BAND = 1.15f

    /**
     * The cover's side: [COVER_DP], less where the window is narrower than 360 dp (20 dp each side) or too short to leave
     * [COVER_GAP_DP] between the cover and the stack of [stackHeightDp] under it. Never below [COVER_MIN_DP].
     * A stack not measured yet (0) counts as empty.
     */
    fun coverSideDp(windowWidthDp: Float, windowHeightDp: Float, stackHeightDp: Float): Float {
        val byWidth = windowWidthDp - 2f * SIDE_PADDING_DP
        val byHeight = windowHeightDp - max(stackHeightDp, 0f) - COVER_GAP_DP - COVER_TOP_DP
        return max(COVER_MIN_DP, min(COVER_DP, min(byWidth, byHeight)))
    }

    /** The chip slot's width at [reveal] (0 = cover, 1 = video): it opens from nothing to [CHIP_SLOT_DP]. */
    fun chipSlotDp(reveal: Float): Float = CHIP_SLOT_DP * reveal.coerceIn(0f, 1f)

    /** The scale of the travelling cover at [reveal]: 1 at rest, [CHIP_DP] / [sideDp] at the end. Not clamped, so a spring may overshoot. */
    fun coverScale(reveal: Float, sideDp: Float): Float = 1f + (CHIP_DP / sideDp - 1f) * reveal

    /** How far the cover has gone along one axis at [reveal], from its centre [from] to the chip's centre [to]. Not clamped. */
    fun travel(reveal: Float, from: Float, to: Float): Float = (to - from) * reveal

    /**
     * How much of the trip's last stretch is done (0 until 70 %, 1 at the end, a smooth step between): the travelling cover
     * fades out by this much and the real 32 dp picture in the chip slot fades in. A picture's corner radius is a
     * composition parameter, so a radius that glides from 28 to 9 would rebuild the image every frame; two pictures of the
     * same cover handing over where they are the same size give the same look.
     */
    fun chipShare(reveal: Float): Float {
        val t = ((reveal - HANDOVER_FROM) / (1f - HANDOVER_FROM)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Which of the title's instances (0..[TITLE_STEPS]) its arrival [progress] (0 to 1, a spring may overshoot) is at. */
    fun titleStep(progress: Float): Int = (progress * TITLE_STEPS).roundToInt().coerceIn(0, TITLE_STEPS)

    /** The title's letter spacing in em at [step]: `0.01` when it arrives to `-0.022` when it has settled. */
    fun titleTrackingEm(step: Int): Float =
        TRACKING_START_EM + (TRACKING_END_EM - TRACKING_START_EM) * (step.coerceIn(0, TITLE_STEPS).toFloat() / TITLE_STEPS)

    /** `m:ss`, minutes without a limit (an hour is `60:00`); a negative time is zero. */
    fun formatTime(ms: Long): String {
        val seconds = if (ms <= 0L) 0L else ms / 1000L
        val rest = seconds % 60L
        return "${seconds / 60L}:${if (rest < 10L) "0" else ""}$rest"
    }

    /** What is left of the song, with a minus: `-2:00`; never below `-0:00`. */
    fun formatRemaining(positionMs: Long, durationMs: Long): String = "-" + formatTime(max(0L, durationMs - positionMs))
}

/** Which arrangement of Now Playing a window gets: portrait (the default), landscape (a phone on its side) or wide (the desktop). */
enum class NowPlayingLayout {
    PORTRAIT, LANDSCAPE, WIDE;

    companion object {
        /** The width from which a landscape window is the wide, two-column desktop layout. */
        const val WIDE_MIN_DP = 840f

        /** Wide: at least 840 dp and wider than tall. Landscape: wider than tall. Otherwise portrait, a square included. */
        fun choose(windowWidthDp: Float, windowHeightDp: Float): NowPlayingLayout = when {
            windowWidthDp >= WIDE_MIN_DP && windowWidthDp > windowHeightDp -> WIDE
            windowWidthDp > windowHeightDp -> LANDSCAPE
            else -> PORTRAIT
        }
    }
}
