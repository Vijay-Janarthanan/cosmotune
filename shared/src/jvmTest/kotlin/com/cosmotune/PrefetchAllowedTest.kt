package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The next song's video is measured in the background only when both switches are on, the song can be looked up, and the
 * connection is not metered. The table is written out by hand, all 16 combinations, so that nothing in it is computed by
 * the code under test.
 */
class PrefetchAllowedTest {

    private class Row(
        val animatedCanvas: Boolean,
        val openCanvasEnabled: Boolean,
        val metered: Boolean,
        val hasIds: Boolean,
        val allowed: Boolean,
    ) {
        override fun toString() =
            "animatedCanvas=$animatedCanvas openCanvasEnabled=$openCanvasEnabled metered=$metered hasIds=$hasIds"
    }

    private fun row(animatedCanvas: Boolean, openCanvasEnabled: Boolean, metered: Boolean, hasIds: Boolean, allowed: Boolean) =
        Row(animatedCanvas, openCanvasEnabled, metered, hasIds, allowed)

    private val table = listOf(
        //  animatedCanvas, openCanvasEnabled, metered, hasIds -> allowed
        row(animatedCanvas = false, openCanvasEnabled = false, metered = false, hasIds = false, allowed = false),
        row(animatedCanvas = false, openCanvasEnabled = false, metered = false, hasIds = true, allowed = false),
        row(animatedCanvas = false, openCanvasEnabled = false, metered = true, hasIds = false, allowed = false),
        row(animatedCanvas = false, openCanvasEnabled = false, metered = true, hasIds = true, allowed = false),
        row(animatedCanvas = false, openCanvasEnabled = true, metered = false, hasIds = false, allowed = false),
        row(animatedCanvas = false, openCanvasEnabled = true, metered = false, hasIds = true, allowed = false), // animated cover off
        row(animatedCanvas = false, openCanvasEnabled = true, metered = true, hasIds = false, allowed = false),
        row(animatedCanvas = false, openCanvasEnabled = true, metered = true, hasIds = true, allowed = false),
        row(animatedCanvas = true, openCanvasEnabled = false, metered = false, hasIds = false, allowed = false),
        row(animatedCanvas = true, openCanvasEnabled = false, metered = false, hasIds = true, allowed = false), // OpenCanvas off
        row(animatedCanvas = true, openCanvasEnabled = false, metered = true, hasIds = false, allowed = false),
        row(animatedCanvas = true, openCanvasEnabled = false, metered = true, hasIds = true, allowed = false),
        row(animatedCanvas = true, openCanvasEnabled = true, metered = false, hasIds = false, allowed = false), // nothing to look up
        row(animatedCanvas = true, openCanvasEnabled = true, metered = false, hasIds = true, allowed = true), // Wi-Fi: the one case
        row(animatedCanvas = true, openCanvasEnabled = true, metered = true, hasIds = false, allowed = false),
        row(animatedCanvas = true, openCanvasEnabled = true, metered = true, hasIds = true, allowed = false), // mobile data
    )

    private fun answer(r: Row) = prefetchAllowed(
        animatedCanvas = r.animatedCanvas,
        openCanvasEnabled = r.openCanvasEnabled,
        metered = r.metered,
        hasIds = r.hasIds,
    )

    @Test
    fun theTableHasEveryCombinationExactlyOnce() {
        val combinations = table.map { listOf(it.animatedCanvas, it.openCanvasEnabled, it.metered, it.hasIds) }
        assertEquals(16, combinations.size)
        assertEquals(16, combinations.toSet().size)
    }

    @Test
    fun allSixteenCombinationsGiveTheWrittenAnswer() {
        val wrong = table.filter { answer(it) != it.allowed }
        assertTrue(wrong.isEmpty(), "wrong answer for (${wrong.size}):\n" + wrong.joinToString("\n") { "$it, expected ${it.allowed}" })
    }

    @Test
    fun exactlyOneCombinationIsAllowed() {
        assertEquals(1, table.count { it.allowed })
        assertTrue(prefetchAllowed(animatedCanvas = true, openCanvasEnabled = true, metered = false, hasIds = true))
    }

    @Test
    fun aMeteredConnectionNeverAllowsItWhateverTheOtherAnswersAre() {
        for (r in table.filter { it.metered }) assertFalse(answer(r), "metered must never prefetch: $r")
    }
}
