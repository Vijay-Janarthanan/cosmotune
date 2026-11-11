package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Instrumental breaks on line-synced lyrics that state when each line ends. Invented lines only. */
class LyricGapsTest {

    private fun lineSynced(vararg rows: Triple<Long, Long?, String>) = rows.map { (time, duration, text) ->
        LyricLine(time, text, sungUntilMs = duration?.takeIf { it > 0 }?.let { time + it })
    }.withInstrumentalGaps()

    private fun List<LyricLine>.sung() = filterNot { it.isGap }

    /**
     * Each line's duration running right up to the next stamp: ten seconds
     * between stamps is one line sung over ten seconds, not a ten-second
     * break, so nothing but the intro should be marked.
     */
    @Test
    fun `a slowly sung line-synced song gets no break between its lines`() {
        val lines = lineSynced(
            Triple(19_740L, 10_020L, "A lantern on the water"),
            Triple(29_760L, 9_910L, "A kite above the hill"),
            Triple(39_670L, 10_000L, "A bell across the field"),
        )
        // Only the 19.7s run-up before the first word.
        assertEquals(1, lines.count { it.isGap })
        assertTrue(lines.first().isGap)
        assertEquals(3, lines.sung().size)
    }

    /**
     * A break stamped at the same millisecond as a line shadows it forever,
     * because the cursor takes the *last* line whose stamp has passed.
     */
    @Test
    fun `no break ever shares a stamp with the line it follows`() {
        val lines = lineSynced(
            Triple(19_740L, 10_020L, "one"),
            Triple(29_760L, 9_910L, "two"),
            Triple(39_670L, 10_000L, "three"),
        )
        val sungStamps = lines.sung().map { it.timeMs }.toSet()
        assertEquals(emptyList(), lines.filter { it.isGap && it.timeMs in sungStamps })
    }

    /** With no duration to go on, the distance to the next stamp proves nothing. */
    @Test
    fun `a line-synced source with no durations gets no synthesised breaks`() {
        val lines = lineSynced(
            Triple(1_000L, null, "one"),
            Triple(20_000L, null, "two"),
        )
        assertEquals(0, lines.count { it.isGap })
    }

    /** A stated end well short of the next line is a real break, and is drawn. */
    @Test
    fun `a stated line end short of the next stamp is marked as a break`() {
        val lines = lineSynced(
            Triple(1_000L, 2_000L, "before the solo"),
            Triple(30_000L, 2_000L, "after the solo"),
        )
        val gap = lines.single { it.isGap && it.timeMs > 0 }
        // The note lands when the singing stopped, not when the next line was due.
        assertEquals(3_000L, gap.timeMs)
    }
}
