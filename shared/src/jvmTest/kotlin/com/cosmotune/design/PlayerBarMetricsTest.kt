package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop player capsule (`V2.deskPlayer`, `screens-v2.css:225-233`): the designer's numbers, and what the capsule drops
 * when the window leaves it less room than the board's 1440 dp wide frame did (the lyrics or queue column takes 376 dp).
 */
class PlayerBarMetricsTest {

    @Test
    fun theNumbersAreTheDesignersValues() {
        assertEquals(80f, PlayerBarMetrics.HEIGHT_DP)
        assertEquals(ShellMetrics.DESKTOP_BAR_HEIGHT_DP, PlayerBarMetrics.HEIGHT_DP, "the clearance maths and the bar agree on its height")
        assertEquals(28f, PlayerBarMetrics.RADIUS_DP)
        assertEquals(16f, PlayerBarMetrics.PADDING_DP)
        assertEquals(12f, PlayerBarMetrics.GAP_DP)
        assertEquals(48f, PlayerBarMetrics.COVER_DP)
        assertEquals(220f, PlayerBarMetrics.TEXT_MAX_DP)
        assertEquals(40f, PlayerBarMetrics.BUTTON_DP)
        assertEquals(48f, PlayerBarMetrics.PLAY_DP)
        assertEquals(8f, PlayerBarMetrics.TRANSPORT_GAP_DP)
        assertEquals(44f, PlayerBarMetrics.TIME_DP)
        assertEquals(8f, PlayerBarMetrics.SCRUB_GAP_DP)
        assertEquals(560f, PlayerBarMetrics.SCRUB_MAX_DP)
        assertEquals(16f, PlayerBarMetrics.WAVE_HEIGHT_DP)
        assertEquals(92f, PlayerBarMetrics.VOLUME_DP)
    }

    @Test
    fun aWideWindowGetsTheWholeBoard() {
        val plan = PlayerBarMetrics.plan(1200f)
        assertEquals(PlayerBarMetrics.Tier.FULL, plan.tier)
        assertEquals(220f, plan.textDp)
        assertTrue(plan.like && plan.shuffleRepeat && plan.scrubber && plan.output && plan.volume)
    }

    @Test
    fun theBoardsOwnFrameIsFull() {
        // The 1440 dp board, less the 220 dp sidebar and the 16 dp margins, with the queue column open: 1440 - 236 - 376.
        assertEquals(PlayerBarMetrics.Tier.FULL, PlayerBarMetrics.plan(1440f - 236f - 16f).tier)
    }

    @Test
    fun eachTierIsChosenAtExactlyTheWidthItNeeds() {
        for (tier in PlayerBarMetrics.Tier.entries) {
            val need = PlayerBarMetrics.requiredDp(PlayerBarMetrics.planFor(tier))
            assertEquals(tier, PlayerBarMetrics.plan(need).tier, "$tier at its own width ($need dp)")
            if (tier != PlayerBarMetrics.Tier.MINIMAL) {
                assertTrue(PlayerBarMetrics.plan(need - 0.5f).tier.ordinal > tier.ordinal, "$tier is not chosen half a dp below its width")
            }
        }
    }

    @Test
    fun tiersGetPoorerAsTheWidthShrinks() {
        var previous = PlayerBarMetrics.plan(3000f)
        var w = 3000f
        while (w >= 300f) {
            val plan = PlayerBarMetrics.plan(w)
            assertTrue(plan.tier.ordinal >= previous.tier.ordinal, "tier never gets richer while the width shrinks (at $w dp)")
            // Every feature a poorer tier has, the richer one has too.
            if (previous.tier != plan.tier) {
                assertTrue(plan.textDp <= previous.textDp || plan.tier == PlayerBarMetrics.Tier.MINIMAL)
                assertTrue(!plan.like || previous.like)
                assertTrue(!plan.shuffleRepeat || previous.shuffleRepeat)
                assertTrue(!plan.scrubber || previous.scrubber)
                assertTrue(!plan.output || previous.output)
                assertTrue(!plan.volume || previous.volume)
            }
            previous = plan
            w -= 1f
        }
    }

    @Test
    fun theVolumeGoesFirstThenTheShuffleAndTheRepeat() {
        assertTrue(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL).volume)
        val medium = PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.MEDIUM)
        assertFalse(medium.volume)
        assertTrue(medium.shuffleRepeat && medium.scrubber && medium.output)
        val compact = PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.COMPACT)
        assertFalse(compact.shuffleRepeat)
        assertTrue(compact.scrubber && compact.like && compact.output)
        val minimal = PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.MINIMAL)
        assertFalse(minimal.scrubber || minimal.like || minimal.output || minimal.shuffleRepeat || minimal.volume)
    }

    @Test
    fun theLyricsAndQueueButtonsAreNeverDropped() {
        // They are the way to put the side column away again; every plan keeps the two (the plan has no flag to drop them).
        val narrowest = PlayerBarMetrics.requiredDp(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.MINIMAL))
        assertTrue(narrowest >= 2 * PlayerBarMetrics.PADDING_DP + 2 * PlayerBarMetrics.TOUCH_DP, "the least width still holds the two")
    }

    @Test
    fun whateverTheWidthThePlanFits() {
        var w = 300f
        while (w <= 2600f) {
            val plan = PlayerBarMetrics.plan(w)
            if (w >= PlayerBarMetrics.requiredDp(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.MINIMAL))) {
                assertTrue(PlayerBarMetrics.requiredDp(plan) <= w, "plan ${plan.tier} needs ${PlayerBarMetrics.requiredDp(plan)} dp of $w dp")
            }
            w += 1f
        }
    }

    @Test
    fun theNarrowestBarTheHostCanMakeStillFitsItsMinimalPlan() {
        // DesktopFrame goes compact below 980 dp; from there the host draws no bar. At 980 dp with the side column open the
        // bar runs from the sidebar plus a margin to the column plus a margin.
        val window = ShellMetrics.DESKTOP_COMPACT_DP
        val start = ShellMetrics.DESKTOP_SIDEBAR_DP + 16f
        val end = ShellMetrics.DESKTOP_SIDE_PANEL_DP + 16f
        val narrowest = window - start - end
        assertEquals(368f, narrowest)
        assertTrue(PlayerBarMetrics.requiredDp(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.MINIMAL)) <= narrowest)
        assertEquals(PlayerBarMetrics.Tier.MINIMAL, PlayerBarMetrics.plan(narrowest).tier)
    }

    @Test
    fun aTypicalWindowWithTheColumnOpenKeepsTheScrubber() {
        // 1280 dp wide, lyrics open: 1280 - 236 - 376 = 668 dp.
        val plan = PlayerBarMetrics.plan(668f)
        assertTrue(plan.scrubber && plan.like && plan.output)
        assertFalse(plan.volume)
    }

}
