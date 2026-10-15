package com.cosmotune.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The little motes that rise off a line's translation when it changes: where they start, and how they move. */
class LyricParticlesTest {
    private val text = "and it lasted the whole afternoon, every window is a weather report"

    @Test
    fun aLineGetsAtMostEighteenOfThem() {
        val set = LyricParticles.generate(text)
        assertEquals(18, set.size)
        assertEquals(18, LyricParticles.PER_VOICE)
    }

    @Test
    fun aShortLineGetsOneForEachLetterAndDigitItHas() {
        assertEquals(3, LyricParticles.generate("a, b!  7").size)
        assertEquals(0, LyricParticles.generate("... !? ").size)
        assertEquals(0, LyricParticles.generate("").size)
    }

    @Test
    fun theyStartOnLettersAndDigitsAndEachOnADifferentOne() {
        val set = LyricParticles.generate(text)
        val chars = (0 until set.size).map { set.charIndex[it] }
        assertTrue(chars.all { text[it].isLetterOrDigit() })
        assertEquals(chars.size, chars.toSet().size)
    }

    @Test
    fun theSameLineAlwaysGetsTheSameMotes() {
        val a = LyricParticles.generate(text)
        val b = LyricParticles.generate(text)
        assertEquals(a.charIndex.toList(), b.charIndex.toList())
        assertEquals(a.driftX.toList(), b.driftX.toList())
        assertEquals(a.driftY.toList(), b.driftY.toList())
        assertEquals(a.radius.toList(), b.radius.toList())
        assertEquals(a.delay.toList(), b.delay.toList())
    }

    @Test
    fun aDifferentLineGetsDifferentMotes() {
        assertTrue(LyricParticles.generate(text).charIndex.toList() != LyricParticles.generate(text.reversed()).charIndex.toList())
    }

    @Test
    fun theyDriftUpAndSidewaysByAFewDpAndAreAboutAPixelAcross() {
        val set = LyricParticles.generate(text)
        for (i in 0 until set.size) {
            assertTrue(set.driftX[i] in -6f..6f, "x ${set.driftX[i]}")
            assertTrue(set.driftY[i] in -16f..-5f, "y ${set.driftY[i]}")
            assertTrue(set.radius[i] in 0.65f..1.3f, "radius ${set.radius[i]}")
            assertTrue(set.delay[i] in 0f..0.16f, "delay ${set.delay[i]}")
        }
    }

    @Test
    fun theLaterInTheLineAMoteStartsTheLaterItSetsOff() {
        val set = LyricParticles.generate(text)
        for (a in 0 until set.size) for (b in 0 until set.size) {
            if (set.charIndex[a] < set.charIndex[b]) assertTrue(set.delay[a] < set.delay[b])
        }
    }

    // ---- the motion -----------------------------------------------------------------------------------------------------

    @Test
    fun aMoteWaitsForItsDelayThenRunsOverEightyFourHundredthsOfTheMotion() {
        assertEquals(0f, LyricParticles.phase(progress = 0.1f, delay = 0.1f), 0f)
        assertEquals(0f, LyricParticles.phase(progress = 0.05f, delay = 0.1f), 0f)
        assertEquals(0.5f, LyricParticles.phase(progress = 0.1f + 0.42f, delay = 0.1f), 1e-5f)
        assertEquals(1f, LyricParticles.phase(progress = 1f, delay = 0.16f), 1e-6f)
    }

    @Test
    fun aMoteFadesInAndOutAndIsNeverSeenAtTheEnds() {
        assertEquals(0f, LyricParticles.envelope(0f), 1e-6f)
        assertEquals(1f, LyricParticles.envelope(0.5f), 1e-6f)
        assertEquals(0f, LyricParticles.envelope(1f), 1e-5f)
    }

    @Test
    fun aMoteSlowsAsItRises() {
        assertEquals(0f, LyricParticles.ease(0f), 0f)
        assertEquals(0.75f, LyricParticles.ease(0.5f), 1e-6f)
        assertEquals(1f, LyricParticles.ease(1f), 0f)
    }

    @Test
    fun theHaloIsFaintAndTheCoreIsStronger() {
        assertTrue(LyricParticles.HALO_ALPHA < LyricParticles.CORE_ALPHA)
        assertEquals(2.7f, LyricParticles.HALO_RADIUS_RATIO, 0f)
        assertEquals(540, LyricParticles.MOTION_MS)
    }
}
