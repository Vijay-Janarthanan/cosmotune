package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sizes of `v3/01-components-spec-bc.md` and `components-v3bc.css`, by control. */
class ControlMetricsTest {
    @Test
    fun buttonsAreFortyFortyEightAndFiftySixWithPaddingOfFortySixPercentOfTheirHeight() {
        assertEquals(40f, ControlMetrics.BUTTON_SMALL_DP)
        assertEquals(48f, ControlMetrics.BUTTON_MEDIUM_DP)
        assertEquals(56f, ControlMetrics.BUTTON_LARGE_DP)
        assertEquals(18.4f, ControlMetrics.buttonPaddingDp(40f), 0.001f)
        assertEquals(22.08f, ControlMetrics.buttonPaddingDp(48f), 0.001f)
        assertEquals(25.76f, ControlMetrics.buttonPaddingDp(56f), 0.001f)
    }

    @Test
    fun buttonTextAndIconStepWithTheHeight() {
        assertEquals(14f, ControlMetrics.buttonFontSp(40f)); assertEquals(15f, ControlMetrics.buttonFontSp(48f)); assertEquals(16f, ControlMetrics.buttonFontSp(56f))
        assertEquals(20f, ControlMetrics.buttonIconDp(40f)); assertEquals(20f, ControlMetrics.buttonIconDp(48f)); assertEquals(22f, ControlMetrics.buttonIconDp(56f))
    }

    @Test
    fun iconButtonsCarryTwentyTwentyFourAndTwentyEightDpIcons() {
        assertEquals(20f, ControlMetrics.iconButtonIconDp(40f)); assertEquals(24f, ControlMetrics.iconButtonIconDp(48f)); assertEquals(28f, ControlMetrics.iconButtonIconDp(56f))
        assertEquals(24f, ControlMetrics.iconButtonIconDp(44f), "an odd size takes the nearest step")
    }

    @Test
    fun theHeroPlayIconIsFortyPercentOfTheButtonAndItsLoadingBlobThirtyTwo() {
        assertEquals(32f, ControlMetrics.playIconDp(80f), 0.001f)
        assertEquals(17.6f, ControlMetrics.playIconDp(44f), 0.001f)
        assertEquals(25.6f, ControlMetrics.playBlobDp(80f), 0.001f)
    }

    @Test
    fun chipsAreThirtySixOrFortyFourHighWithFourteenDpPadding() {
        assertEquals(36f, ControlMetrics.chipHeightDp(big = false)); assertEquals(44f, ControlMetrics.chipHeightDp(big = true))
        assertEquals(14f, ControlMetrics.CHIP_PADDING_DP); assertEquals(18f, ControlMetrics.CHIP_ICON_DP)
        assertEquals(14f, ControlMetrics.chipFontSp(big = false)); assertEquals(15f, ControlMetrics.chipFontSp(big = true))
    }

    @Test
    fun theSwitchThumbGrowsFromSixteenAtEightEightToTwentyFourAtTwentyFourFour() {
        val out = FloatArray(3)
        ControlMetrics.switchThumb(0f, out); assertEquals(listOf(16f, 8f, 8f), out.toList())
        ControlMetrics.switchThumb(1f, out); assertEquals(listOf(24f, 24f, 4f), out.toList())
        ControlMetrics.switchThumb(0.5f, out); assertEquals(listOf(20f, 16f, 6f), out.toList())
        // the thumb stays centred on the track's middle line at every step
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            ControlMetrics.switchThumb(p, out)
            assertEquals(ControlMetrics.SWITCH_HEIGHT_DP / 2f, out[2] + out[0] / 2f, 0.001f, "centre at $p")
        }
    }

    @Test
    fun theSwitchThumbMayOvershootWithTheSpring() {
        val out = FloatArray(3)
        ControlMetrics.switchThumb(1.015f, out)
        assertTrue(out[0] > 24f && out[1] > 24f, "it follows p past 1: ${out.toList()}")
    }

    @Test
    fun segmentsKeepTheOuterEndsAPillAndTakeTheMorphingRadiusOnTheInnerSides() {
        val out = FloatArray(4)   // top-left, top-right, bottom-right, bottom-left
        ControlMetrics.segmentCorners(index = 0, count = 3, innerRadiusDp = 6f, out = out); assertEquals(listOf(20f, 6f, 6f, 20f), out.toList())
        ControlMetrics.segmentCorners(index = 1, count = 3, innerRadiusDp = 6f, out = out); assertEquals(listOf(6f, 6f, 6f, 6f), out.toList())
        ControlMetrics.segmentCorners(index = 2, count = 3, innerRadiusDp = 6f, out = out); assertEquals(listOf(6f, 20f, 20f, 6f), out.toList())
        ControlMetrics.segmentCorners(index = 0, count = 1, innerRadiusDp = 6f, out = out); assertEquals(listOf(20f, 20f, 20f, 20f), out.toList(), "a lone segment is a pill")
        ControlMetrics.segmentCorners(index = 1, count = 3, innerRadiusDp = 20f, out = out); assertEquals(listOf(20f, 20f, 20f, 20f), out.toList(), "selected: inner radius 20 makes a pill")
    }

    @Test
    fun theFloatingSegmentedControlIsAGlassTrackOfRadius24WithA32DpThumb() {
        assertEquals(4f, ControlMetrics.C_SEGMENT_TRACK_PADDING_DP); assertEquals(24f, ControlMetrics.C_SEGMENT_TRACK_RADIUS_DP); assertEquals(32f, ControlMetrics.C_SEGMENT_THUMB_DP)
        assertEquals(ControlMetrics.SEGMENT_HEIGHT_DP, ControlMetrics.C_SEGMENT_THUMB_DP + 2 * ControlMetrics.C_SEGMENT_TRACK_PADDING_DP, "the track is as tall as a B segment")
    }

    @Test
    fun theHighlightsAreTheSpecOpacities() {
        assertEquals(0.10f, ControlMetrics.highlightPrimary(dark = true)); assertEquals(0.18f, ControlMetrics.highlightPrimary(dark = false))
        assertEquals(0.06f, ControlMetrics.HIGHLIGHT_TONAL); assertEquals(0.22f, ControlMetrics.HIGHLIGHT_PLAY)
        assertEquals(0.28f, ControlMetrics.C_PRIMARY_TOP_HIGHLIGHT); assertEquals(0.30f, ControlMetrics.C_PLAY_TOP_HIGHLIGHT)
        assertEquals(0.14f, ControlMetrics.glassRing(dark = true)); assertEquals(0.10f, ControlMetrics.glassRing(dark = false))
        assertEquals(0.16f, ControlMetrics.glassRingSecondary(dark = true)); assertEquals(0.22f, ControlMetrics.glassTopHighlight(dark = true)); assertEquals(0.80f, ControlMetrics.glassTopHighlight(dark = false))
        assertEquals(0.46f, ControlMetrics.C_PRIMARY_COLOUR_SHARE); assertEquals(0.72f, ControlMetrics.C_PLAY_COLOUR_SHARE)
    }
}
