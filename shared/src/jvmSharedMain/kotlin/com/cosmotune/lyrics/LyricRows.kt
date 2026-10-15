package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.isGeniusSectionHeader

/**
 * What a touch on a line of lyrics does, as plain decisions (the old panel's row, `PlayerLyrics.kt`): a tap seeks to the line,
 * a long press starts choosing lines for a card, and while lines are being chosen a tap adds the line to the choice or drops it.
 */
object LyricRows {
    enum class OnTap { NOTHING, SEEK, TOGGLE_PICK }

    enum class OnLongPress { NOTHING, PICK }

    /** How a row shows its place in a pick. */
    enum class Mark { NONE, PICKED, OTHER }

    /** What a tap on a line does: add or drop it while picking, seek when the song is stamped, else nothing. */
    fun onTap(synced: Boolean, picking: Boolean): OnTap = when {
        picking -> OnTap.TOGGLE_PICK
        synced -> OnTap.SEEK
        else -> OnTap.NOTHING
    }

    /**
     * What a long press does: where a card can be made it starts a pick, or, once one is open, is a tap on that line. The old
     * row's gesture was enabled only when "picking or synced", so a plain-text song could not start one; kept as it was.
     */
    fun onLongPress(canPick: Boolean, synced: Boolean, picking: Boolean): OnLongPress =
        if (canPick && (picking || synced)) OnLongPress.PICK else OnLongPress.NOTHING

    /** Whether a line responds to touch at all: when there is something to do with it. An unstamped line outside a pick leaves its taps alone. */
    fun responds(synced: Boolean, picking: Boolean): Boolean = picking || synced

    /** Whether a tap on a break (an instrumental) seeks: not while a pick is open, where a stray tap in the silence would move the song. */
    fun breakSeeks(synced: Boolean, picking: Boolean): Boolean = synced && !picking

    /** Whether [line] is drawn as a section header ("[Chorus]") rather than as words: only in a song with no timings (a Genius page). */
    fun isHeader(line: LyricLine, synced: Boolean): Boolean = !synced && isGeniusSectionHeader(line.text)

    /** How line [index] shows itself while [picking]: the chosen ones lit, the rest stepped back; outside a pick, as usual. */
    fun mark(index: Int, picking: Boolean, picked: Set<Int>): Mark = when {
        !picking -> Mark.NONE
        index in picked -> Mark.PICKED
        else -> Mark.OTHER
    }
}
