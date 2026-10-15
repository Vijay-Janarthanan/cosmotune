package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a touch on a lyric line does: seek, start picking lines for a card, or add the line to a pick. These are the old
 * panel's rules (`PlayerLyrics.kt`, the row's `combinedClickable`) as plain decisions.
 */
class LyricRowsTest {

    // ---- a tap ---------------------------------------------------------------------------------------------------------

    @Test
    fun aTapSeeksWhenTheLinesAreStampedAndDoesNothingWhenTheyAreNot() {
        assertEquals(LyricRows.OnTap.SEEK, LyricRows.onTap(synced = true, picking = false))
        assertEquals(LyricRows.OnTap.NOTHING, LyricRows.onTap(synced = false, picking = false))
    }

    @Test
    fun whileLinesAreBeingPickedATapAddsOrDropsTheLineWhateverTheSource() {
        assertEquals(LyricRows.OnTap.TOGGLE_PICK, LyricRows.onTap(synced = true, picking = true))
        assertEquals(LyricRows.OnTap.TOGGLE_PICK, LyricRows.onTap(synced = false, picking = true))
    }

    // ---- a long press --------------------------------------------------------------------------------------------------

    @Test
    fun aLongPressStartsAPickWhereACardCanBeMade() {
        assertEquals(LyricRows.OnLongPress.PICK, LyricRows.onLongPress(canPick = true, synced = true, picking = false))
    }

    @Test
    fun whereNoCardCanBeMadeALongPressDoesNothing() {
        assertEquals(LyricRows.OnLongPress.NOTHING, LyricRows.onLongPress(canPick = false, synced = true, picking = false))
        assertEquals(LyricRows.OnLongPress.NOTHING, LyricRows.onLongPress(canPick = false, synced = false, picking = false))
    }

    @Test
    fun aLongPressWhilePickingIsATapOnThatLine() {
        assertEquals(LyricRows.OnLongPress.PICK, LyricRows.onLongPress(canPick = true, synced = true, picking = true))
        assertEquals(LyricRows.OnLongPress.PICK, LyricRows.onLongPress(canPick = true, synced = false, picking = true))
    }

    @Test
    fun theOldPanelOnlyStartedAPickOnLinesThatWereStamped() {
        // kept as it was: the row's gesture is enabled when "picking || synced", so a plain-text song cannot start one
        assertEquals(LyricRows.OnLongPress.NOTHING, LyricRows.onLongPress(canPick = true, synced = false, picking = false))
    }

    @Test
    fun aRowRespondsToAnythingOnlyWhenItHasSomethingToDo() {
        assertTrue(LyricRows.responds(synced = true, picking = false))
        assertTrue(LyricRows.responds(synced = false, picking = true))
        assertFalse(LyricRows.responds(synced = false, picking = false))
    }

    @Test
    fun aBreakSeeksOnATapButNeverWhileAPickIsOpen() {
        assertTrue(LyricRows.breakSeeks(synced = true, picking = false))
        assertFalse(LyricRows.breakSeeks(synced = true, picking = true))
        assertFalse(LyricRows.breakSeeks(synced = false, picking = false))
    }

    // ---- what a row is --------------------------------------------------------------------------------------------------

    @Test
    fun aGeniusSectionMarkerIsDrawnAsAHeaderOnlyInAPlainSong() {
        assertTrue(LyricRows.isHeader(LyricLine(0L, "[Chorus]"), synced = false))
        assertTrue(LyricRows.isHeader(LyricLine(0L, "[Verse 2: Someone]"), synced = false))
        assertFalse(LyricRows.isHeader(LyricLine(5_000L, "[spoken]"), synced = true), "in a stamped song a line in brackets is a line")
        assertFalse(LyricRows.isHeader(LyricLine(0L, "chorus"), synced = false))
        assertFalse(LyricRows.isHeader(LyricLine(0L, ""), synced = false))
    }

    // ---- how a row shows it ----------------------------------------------------------------------------------------------

    @Test
    fun aChosenLineIsMarkedAndTheOthersStepBackWhileAPickIsOpen() {
        val picked = setOf(1, 4)
        assertEquals(LyricRows.Mark.PICKED, LyricRows.mark(1, picking = true, picked = picked))
        assertEquals(LyricRows.Mark.PICKED, LyricRows.mark(4, picking = true, picked = picked))
        assertEquals(LyricRows.Mark.OTHER, LyricRows.mark(2, picking = true, picked = picked))
    }

    @Test
    fun outsideAPickNothingIsMarked() {
        assertEquals(LyricRows.Mark.NONE, LyricRows.mark(1, picking = false, picked = setOf(1)))
        assertEquals(LyricRows.Mark.NONE, LyricRows.mark(2, picking = false, picked = emptySet()))
    }
}
