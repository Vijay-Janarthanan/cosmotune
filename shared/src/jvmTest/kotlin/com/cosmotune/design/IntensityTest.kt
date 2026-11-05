package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings > Appearance > Intensity: `v2/01-dynamic-colour.md` section 3, rule 7. */
class IntensityTest {
    @Test fun calmBalancedAndRichMultiplyBySevenTenthsOneAndFiveFourths() {
        assertEquals(0.7, Intensity.multiplier(Intensity.CALM), 0.0)
        assertEquals(1.0, Intensity.multiplier(Intensity.BALANCED), 0.0)
        assertEquals(1.25, Intensity.multiplier(Intensity.RICH), 0.0)
    }

    @Test fun theIndexesAreZeroOneTwoAndBalancedIsTheDefault() {
        assertEquals(0, Intensity.CALM)
        assertEquals(1, Intensity.BALANCED)
        assertEquals(2, Intensity.RICH)
        assertEquals(Intensity.BALANCED, Intensity.DEFAULT)
        assertEquals(3, Intensity.COUNT)
    }

    @Test fun anIndexOutsideTheThreeClampsToTheNearestEnd() {
        assertEquals(0.7, Intensity.multiplier(-1), 0.0)
        assertEquals(0.7, Intensity.multiplier(Int.MIN_VALUE), 0.0)
        assertEquals(1.25, Intensity.multiplier(3), 0.0)
        assertEquals(1.25, Intensity.multiplier(Int.MAX_VALUE), 0.0)
    }

    @Test fun clampKeepsAStoredIndexInsideTheThree() {
        assertEquals(0, Intensity.clamp(-5))
        assertEquals(1, Intensity.clamp(1))
        assertEquals(2, Intensity.clamp(9))
    }
}
