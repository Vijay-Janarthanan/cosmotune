package com.cosmotune.lyrics.styles

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun aLineThatWouldRunIntoItselfIsScaledToFitAndNoFurtherThanTheFloor() {
        val radius = 100f
        val full = (2 * PI * radius).toFloat()
        assertEquals(1f, LyricArc.fitScale(full * 0.5f, radius))
        val scale = LyricArc.fitScale(full, radius)
        assertEquals(LyricArc.MAX_SWEEP_DEG, LyricArc.sweepDeg(full * scale, radius), 0.01f)
        assertEquals(0.5f, LyricArc.fitScale(full * 10, radius))
    }

    @Test fun theRingsKeepTheBoardsProportions() {
        val rings = LyricArc.rings(300f)
        assertEquals(78f, rings.disc, 0.01f)
        assertEquals(112f, rings.current, 0.01f)
        assertTrue(rings.next > rings.current + rings.fontPx, "the next line's ring clears the current line's letters")
        assertTrue(rings.disc < rings.current - 8f, "the disc stays inside the line")
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
