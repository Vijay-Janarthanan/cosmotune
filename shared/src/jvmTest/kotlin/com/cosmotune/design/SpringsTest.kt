package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The spring table of `02-design-formulas.md` section 6 (settle time is the envelope under 0.1 %, mass 1). */
class SpringsTest {

    @Test
    fun theSpringValuesAreTheExpressiveTokens() {
        assertEquals(SpringValues(800f, 0.6f), Springs.PRESS)
        assertEquals(SpringValues(380f, 0.8f), Springs.MOVE)
        assertEquals(SpringValues(200f, 0.8f), Springs.SLOW)
        assertEquals(SpringValues(1600f, 1.0f), Springs.EFFECTS)
        assertEquals(700, Springs.PALETTE_MS)
        assertEquals(150, Springs.REDUCED_MS)
    }

    @Test
    fun overshootMatchesTheTable() {
        assertEquals(0.095, Springs.overshoot(Springs.PRESS), 0.001)
        assertEquals(0.015, Springs.overshoot(Springs.MOVE), 0.001)
        assertEquals(0.015, Springs.overshoot(Springs.SLOW), 0.001)
        assertEquals(0.0, Springs.overshoot(Springs.EFFECTS), 0.0)
    }

    @Test
    fun settleTimeMatchesTheTable() {
        assertEquals(421.0, Springs.settleMs(Springs.PRESS), 2.0)
        assertEquals(476.0, Springs.settleMs(Springs.MOVE), 2.0)
        assertEquals(656.0, Springs.settleMs(Springs.SLOW), 2.0)
        assertEquals(231.0, Springs.settleMs(Springs.EFFECTS), 2.0)
    }

    @Test
    fun aTighterToleranceTakesLonger() {
        assertTrue(Springs.settleMs(Springs.PRESS, 0.0001) > Springs.settleMs(Springs.PRESS, 0.01))
        assertTrue(Springs.settleMs(Springs.EFFECTS, 0.0001) > Springs.settleMs(Springs.EFFECTS, 0.01))
    }
}
