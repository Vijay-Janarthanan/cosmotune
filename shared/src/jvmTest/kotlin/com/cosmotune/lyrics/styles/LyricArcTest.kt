package com.cosmotune.lyrics.styles

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricArcTest {
    @Test fun aLineStartsAtTwelveOClockAndRunsClockwise() {
        assertEquals(-90f, LyricArc.angleAt(0f, 100f), 0.001f)
        // A quarter of the circumference is a quarter turn: three o'clock.
        assertEquals(0f, LyricArc.angleAt((PI * 100 / 2).toFloat(), 100f), 0.01f)
        assertEquals(100f, LyricArc.x(0f, 100f, 0f), 0.001f)
        assertEquals(-100f, LyricArc.y(0f, 100f, -90f), 0.001f)
    }

    @Test fun eachGlyphIsTurnedToItsOwnCentre() {
        val lefts = floatArrayOf(0f, 10f, 30f)
        val widths = floatArrayOf(10f, 20f, 10f)
        val out = FloatArray(3)
        LyricArc.place(lefts, widths, 3, 100f, -90f, out)
        assertEquals(LyricArc.angleAt(5f, 100f), out[0], 0.001f)
        assertEquals(LyricArc.angleAt(20f, 100f), out[1], 0.001f)
        assertEquals(LyricArc.angleAt(35f, 100f), out[2], 0.001f)
        assertTrue(out[0] < out[1] && out[1] < out[2], "in reading order, clockwise")
        // The rotation that stands a glyph upright at twelve o'clock is none at all.
        assertEquals(0f, LyricArc.glyphRotation(-90f), 0.001f)
    }

    @Test fun aSmallerRingTurnsTheSameTextFurther() {
        assertTrue(LyricArc.sweepDeg(100f, 50f) > LyricArc.sweepDeg(100f, 100f))
        assertEquals(0f, LyricArc.sweepDeg(100f, 0f))
    }

    @Test fun aLineIsCentredOnTwelveOClockAndNoGlyphTurnsMoreThanSixtyFiveDegrees() {
        assertEquals(-130f, LyricArc.upperStart(80f), 0.001f)
        assertEquals(-155f, LyricArc.UPPER_FROM_DEG)
        assertEquals(-25f, LyricArc.UPPER_TO_DEG)
        assertEquals(65f, LyricArc.glyphRotation(LyricArc.UPPER_TO_DEG), 0.001f)
        assertEquals(-65f, LyricArc.glyphRotation(LyricArc.UPPER_FROM_DEG), 0.001f)
        for (sweep in listOf(10f, 100f, 130f)) {
            val start = LyricArc.upperStart(sweep)
            assertTrue(start >= LyricArc.UPPER_FROM_DEG - 0.001f && start + sweep <= LyricArc.UPPER_TO_DEG + 0.001f, "$sweep stays in the span")
        }
        assertEquals(LyricArc.UPPER_FROM_DEG, LyricArc.spanAngle(0f))
        assertEquals(LyricArc.TOP_DEG, LyricArc.spanAngle(0.5f), 0.001f)
    }

    @Test fun aLineThatFitsTheSpanIsSetWholeOnOneArc() {
        val inner = 100f
        val fit = LyricArc.fitUpper(floatArrayOf(60f, 60f), 2, 10f, inner, inner + 30f)
        assertEquals(1f, fit.scale)
        assertEquals(-1, fit.split)
        assertFalse(fit.flat)
    }

    @Test fun aLongerLineIsSetSmallerDownToThreeQuartersOnOneArc() {
        val inner = 100f
        val arc = LyricArc.upperLength(inner)
        val fit = LyricArc.fitUpper(floatArrayOf(arc * 0.6f, arc * 0.6f), 2, 0f, inner, inner + 30f)
        assertEquals(-1, fit.split)
        assertEquals(1f / 1.2f, fit.scale, 0.001f)
        assertEquals(0.75f, LyricArc.SCALE_FLOOR)
    }

    @Test fun pastTheFloorTheLineIsSplitOverTwoArcsTheFirstWordsOnTheOuter() {
        val inner = 100f
        val outer = 130f
        val arc = LyricArc.upperLength(inner)
        val words = FloatArray(6) { arc * 0.3f }
        val fit = LyricArc.fitUpper(words, 6, 0f, inner, outer)
        assertFalse(fit.flat)
        assertTrue(fit.split in 3..5, "split: ${fit.split}, the outer arc is the longer")
        assertTrue(fit.scale >= LyricArc.SCALE_FLOOR, "two arcs at three quarters or more: ${fit.scale}")
    }

    @Test fun aLineTooLongForTwoArcsIsSetFlatNotWrappedRoundTheRing() {
        val inner = 100f
        val arc = LyricArc.upperLength(inner)
        val fit = LyricArc.fitUpper(FloatArray(12) { arc * 0.3f }, 12, 0f, inner, 130f)
        assertTrue(fit.flat)
        assertEquals(-1, fit.split)
        // A single word longer than the span cannot be split either.
        assertTrue(LyricArc.fitUpper(floatArrayOf(arc * 2f), 1, 0f, inner, 130f).flat)
    }

    @Test fun theNextLineIsSetSmallerToFitAndNoSmallerThanItsFloor() {
        val arc = LyricArc.upperLength(100f)
        assertEquals(1f, LyricArc.nextScale(arc * 0.5f, 100f))
        assertEquals(0.8f, LyricArc.nextScale(arc / 0.8f, 100f), 0.001f)
        assertEquals(LyricArc.NEXT_SCALE_FLOOR, LyricArc.nextScale(arc * 5f, 100f))
    }

    @Test fun theRingsKeepTheLineOutsideTheTrackAndTheArcsAndTheNextLineWellApart() {
        val dp = 2f
        val rings = LyricArc.rings(600f, dp)
        assertTrue(rings.disc < rings.track, "the disc is inside the track")
        assertTrue(rings.inner - rings.fontPx * 0.24f - rings.track >= LyricArc.CLEAR_DP * dp - 0.01f, "8 dp between the track and the descenders")
        assertTrue(rings.outer - rings.inner >= 1.3f * rings.lineHeight - 0.01f, "1.3 line heights between a line's two arcs")
        assertTrue(rings.next - rings.inner >= 1.5f * rings.lineHeight - 0.01f, "the next ring stands 1.5 line heights out")
        assertTrue(rings.nextSplit - rings.outer >= 1.5f * rings.lineHeight - 0.01f, "and from a split line's outer arc too")
        assertTrue(rings.nextSplit + rings.nextFontPx <= 300f, "and inside the stage")
        assertTrue(rings.nextFontPx < rings.fontPx, "the next line is the smaller")
    }
}

class LyricMoonTest {
    @Test fun theMoonFollowsTimeFromTheFirstWordToTheLast() {
        val line = com.cosmotune.music.data.lyrics.LyricLine(
            1_000, "hold on",
            listOf(com.cosmotune.music.data.lyrics.LyricWord(1_200, 1_400, "hold"), com.cosmotune.music.data.lyrics.LyricWord(1_500, 3_200, "on")),
        )
        val lines = listOf(line)
        assertEquals(0f, LyricMoon.fraction(lines, 0, 1_100))
        assertEquals(0.5f, LyricMoon.fraction(lines, 0, 2_200), 0.001f)
        assertEquals(1f, LyricMoon.fraction(lines, 0, 9_000))
        assertEquals(0f, LyricMoon.fraction(lines, 3, 2_000), "no such line")
    }

    @Test fun aLineStampedAtItsStartRunsOverItsSlot() {
        val lines = listOf(com.cosmotune.music.data.lyrics.LyricLine(1_000, "a b"), com.cosmotune.music.data.lyrics.LyricLine(3_000, "c"))
        assertEquals(0.5f, LyricMoon.fraction(lines, 0, 2_000), 0.001f)
    }
}
