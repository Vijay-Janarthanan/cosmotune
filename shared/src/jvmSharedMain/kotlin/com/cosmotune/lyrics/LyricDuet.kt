package com.cosmotune.lyrics

import com.cosmotune.design.LyricsMetrics
import com.cosmotune.music.data.lyrics.LyricAlignment
import com.cosmotune.music.data.lyrics.LyricLine

/**
 * Duets: the decisions the new lyrics view makes to lay the two voices out on opposite sides of the page (the old panel's
 * `duet` and `alignEnd`, `PlayerLyrics.kt`). A line is written from the end side only in a song that names a second voice at
 * all; a single-voice song has every line on the start side already, and splitting it into lanes would only make it narrower.
 */
object LyricDuet {
    /** Whether any line of [lines] is sung from the end side. */
    fun isDuet(lines: List<LyricLine>): Boolean {
        for (i in lines.indices) if (lines[i].alignment == LyricAlignment.End) return true
        return false
    }

    /** Whether [line] sits on the end side of the page: only in a [duet]. */
    fun onEnd(duet: Boolean, line: LyricLine): Boolean = duet && line.alignment == LyricAlignment.End

    /** The lane kept clear on the far side of every line of a duet, in dp; none for a single voice. */
    fun laneDp(duet: Boolean): Float = if (duet) LyricsMetrics.DUET_LANE_DP else 0f
}
