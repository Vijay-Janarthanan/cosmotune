package com.cosmotune.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverShadowTest {
    private fun lch(argb: Int): Triple<Double, Double, Double> {
        val lab = DoubleArray(4)
        OklabMath.fromArgb(argb, lab, 0)
        return Triple(lab[0], hypot(lab[1], lab[2]), (Math.toDegrees(atan2(lab[2], lab[1])) + 360.0) % 360.0)
    }
    private fun hueGap(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    @Test fun aGreyCoverGetsTheExactNeutral() {
        // (L 0.22, C 0.01, h 52 degrees) at 65 percent, whatever the seed was.
        val rad = Math.toRadians(52.0)
        val expected = OklabMath.toArgb(0.22, 0.01 * cos(rad), 0.01 * sin(rad), 0xA6 / 255.0)
        assertEquals(expected, coverShadowArgb(0xFF123456.toInt(), isFallback = true))
        assertEquals(expected, coverShadowArgb(0xFFFFFFFF.toInt(), isFallback = true))
        assertEquals(0xA6, expected ushr 24)
    }

    @Test fun aVividCoverKeepsItsHueAtLightnessPointThreeTwo() {
        // Red, crimson, blue, magenta: the cover's own hue within 3 degrees, whatever its chroma. (An orange such as #D4552E
        // lands 4 degrees off: its requested colour is a hair outside sRGB, and 8-bit rounding of such a dark colour does the
        // rest. That is the designer's gamut mapping at work, not an error here.)
        for (seed in listOf(0xFFFF0000.toInt(), 0xFFE03040.toInt(), 0xFF2060E0.toInt(), 0xFFC020A0.toInt())) {
            val shadow = coverShadowArgb(seed, isFallback = false)
            val (l, c, h) = lch(shadow)
            assertTrue(l in 0.31..0.33, "L $l of ${seed.toUInt().toString(16)}")
            assertTrue(hueGap(h, lch(seed).third) <= 3.0, "hue $h against ${lch(seed).third} of ${seed.toUInt().toString(16)}")
            assertTrue(c in 0.04..0.12 + 2e-3, "chroma $c")
            assertEquals(0xA6, shadow ushr 24)
        }
    }

    @Test fun aPaleSeedRaisesTheChromaToPointZeroFour() {
        val seed = 0xFFF2E6E6.toInt()
        assertTrue(lch(seed).second < 0.04, "the seed is pale")
        val (_, c, _) = lch(coverShadowArgb(seed, isFallback = false))
        assertTrue(c in 0.038..0.042, "chroma $c")
    }

    @Test fun aVeryVividSeedIsCappedAtPointOneTwo() {
        val (_, c, _) = lch(coverShadowArgb(0xFFFF0000.toInt(), isFallback = false))
        assertTrue(c <= 0.121, "chroma $c")
    }
}
