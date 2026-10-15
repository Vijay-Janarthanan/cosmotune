package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hint that says "video is paused on mobile data" is shown only when the existing guard (the animated-cover switch
 * is on, the phone is on a metered connection, and "play over cellular" is off) is what keeps a looked-up video away.
 * The table below is written out by hand, all 16 combinations, so that nothing in it is computed by the code under test.
 */
class CanvasBlockHintTest {

    private class Row(
        val overCellular: Boolean,
        val onCellular: Boolean,
        val canvasOn: Boolean,
        val lookupWanted: Boolean,
        val hint: Boolean,
    ) {
        override fun toString() =
            "overCellular=$overCellular onCellular=$onCellular canvasOn=$canvasOn lookupWanted=$lookupWanted"
    }

    private fun row(overCellular: Boolean, onCellular: Boolean, canvasOn: Boolean, lookupWanted: Boolean, hint: Boolean) =
        Row(overCellular, onCellular, canvasOn, lookupWanted, hint)

    private val table = listOf(
        //  overCellular, onCellular, canvasOn, lookupWanted -> hint
        row(overCellular = false, onCellular = false, canvasOn = false, lookupWanted = false, hint = false),
        row(overCellular = false, onCellular = false, canvasOn = false, lookupWanted = true, hint = false),
        row(overCellular = false, onCellular = false, canvasOn = true, lookupWanted = false, hint = false),
        row(overCellular = false, onCellular = false, canvasOn = true, lookupWanted = true, hint = false), // Wi-Fi
        row(overCellular = false, onCellular = true, canvasOn = false, lookupWanted = false, hint = false),
        row(overCellular = false, onCellular = true, canvasOn = false, lookupWanted = true, hint = false), // switch off
        row(overCellular = false, onCellular = true, canvasOn = true, lookupWanted = false, hint = false), // nothing to look up
        row(overCellular = false, onCellular = true, canvasOn = true, lookupWanted = true, hint = true), // the one case
        row(overCellular = true, onCellular = false, canvasOn = false, lookupWanted = false, hint = false),
        row(overCellular = true, onCellular = false, canvasOn = false, lookupWanted = true, hint = false),
        row(overCellular = true, onCellular = false, canvasOn = true, lookupWanted = false, hint = false),
        row(overCellular = true, onCellular = false, canvasOn = true, lookupWanted = true, hint = false),
        row(overCellular = true, onCellular = true, canvasOn = false, lookupWanted = false, hint = false),
        row(overCellular = true, onCellular = true, canvasOn = false, lookupWanted = true, hint = false),
        row(overCellular = true, onCellular = true, canvasOn = true, lookupWanted = false, hint = false),
        row(overCellular = true, onCellular = true, canvasOn = true, lookupWanted = true, hint = false), // allowed
    )

    private fun answer(r: Row) = canvasBlockedByMobileData(
        overCellular = r.overCellular,
        onCellular = r.onCellular,
        canvasOn = r.canvasOn,
        lookupWanted = r.lookupWanted,
    )

    @Test
    fun theTableHasEveryCombinationExactlyOnce() {
        val combinations = table.map { listOf(it.overCellular, it.onCellular, it.canvasOn, it.lookupWanted) }
        assertEquals(16, combinations.size)
        assertEquals(16, combinations.toSet().size)
    }

    @Test
    fun allSixteenCombinationsGiveTheWrittenAnswer() {
        val wrong = table.filter { answer(it) != it.hint }
        assertTrue(wrong.isEmpty(), "wrong answer for (${wrong.size}):\n" + wrong.joinToString("\n") { "$it, expected ${it.hint}" })
    }

    @Test
    fun exactlyOneCombinationShowsTheHint() {
        assertEquals(1, table.count { it.hint })
        assertTrue(canvasBlockedByMobileData(overCellular = false, onCellular = true, canvasOn = true, lookupWanted = true))
    }

    @Test
    fun allowingMobileDataTakesTheHintAway() {
        assertFalse(canvasBlockedByMobileData(overCellular = true, onCellular = true, canvasOn = true, lookupWanted = true))
    }

    // --- Whether a lookup was wanted at all (the first lines of CanvasRepository.canvasFor) ---------------------------

    @Test
    fun aStreamedSongWantsALookup() {
        assertTrue(canvasLookupWanted(videoId = "dQw4w9WgXcQ", isLocalFile = false, title = "Song", artist = "Artist"))
    }

    @Test
    fun aDownloadedSongKeepsItsVideoIdAndWantsALookup() {
        assertTrue(canvasLookupWanted(videoId = "dQw4w9WgXcQ", isLocalFile = true, title = "Song", artist = "Artist"))
    }

    @Test
    fun aLocalFileWithoutAVideoIdWantsNoLookup() {
        assertFalse(canvasLookupWanted(videoId = "", isLocalFile = true, title = "Song", artist = "Artist"))
        assertFalse(canvasLookupWanted(videoId = "  ", isLocalFile = true, title = "Song", artist = "Artist"))
    }

    @Test
    fun aBlankVideoIdThatIsNotALocalFileStillWantsALookup() {
        assertTrue(canvasLookupWanted(videoId = "", isLocalFile = false, title = "Song", artist = "Artist"))
    }

    @Test
    fun aSongWithoutATitleOrAnArtistWantsNoLookup() {
        assertFalse(canvasLookupWanted(videoId = "x", isLocalFile = false, title = "", artist = "Artist"))
        assertFalse(canvasLookupWanted(videoId = "x", isLocalFile = false, title = "Song", artist = ""))
        assertFalse(canvasLookupWanted(videoId = "x", isLocalFile = false, title = "   ", artist = "Artist"))
        assertFalse(canvasLookupWanted(videoId = "x", isLocalFile = false, title = "Song", artist = "\t"))
    }
}
