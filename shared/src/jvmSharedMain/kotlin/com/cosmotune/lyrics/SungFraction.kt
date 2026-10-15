package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricLine
import kotlin.math.min

/** How much of the current lyric line has been sung, 0 to 1, for the fill that follows the singer. */
object SungFraction {
    /** A line-synced line fills over its slot, but at most this long. */
    const val LINE_FILL_CAP_MS = 6_000L

    /** The last line has no next stamp: it fills over this long. */
    const val LAST_LINE_MS = 4_000L

    fun isSynced(lines: List<LyricLine>) = lines.any { it.timeMs > 0L }

    /** The last line whose stamp has passed, or -1 before the first. */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

    fun of(lines: List<LyricLine>, index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        if (line.text.isEmpty() || positionMs < line.timeMs) return 0f
        if (line.isWordSynced || line.timingSource != null) return (line.revealedChars(positionMs) / line.text.length).coerceIn(0f, 1f)
        val end = line.sungUntilMs
            ?: min(lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + LAST_LINE_MS), line.timeMs + LINE_FILL_CAP_MS)
        if (end <= line.timeMs) return 1f
        return ((positionMs - line.timeMs).toFloat() / (end - line.timeMs)).coerceIn(0f, 1f)
    }
}
