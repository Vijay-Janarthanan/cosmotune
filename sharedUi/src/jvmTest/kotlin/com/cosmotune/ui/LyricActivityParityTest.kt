package com.cosmotune.ui

import com.cosmotune.lyrics.LyricActivity
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import com.cosmotune.music.ui.player.activeLyricRows
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The new view asks [LyricActivity] which lines are being sung on every frame, because [activeLyricRows] builds a list each time.
 * Held here to the real function of the old panel (`LyricFocus.kt`), not to a copy of it, over many songs whose voices overlap.
 */
class LyricActivityParityTest {
    private fun timed(start: Long, end: Long, text: String = "line at $start") =
        LyricLine(start, text, words = listOf(LyricWord(start, end, text)))

    private fun song(random: Random): List<LyricLine> {
        val count = random.nextInt(0, 16)
        val sorted = random.nextInt(4) != 0
        var time = random.nextLong(0, 3_000)
        return List(count) {
            time = if (sorted) time + random.nextLong(200, 4_000) else random.nextLong(0, 38_000)
            val start = time
            when (random.nextInt(6)) {
                0 -> LyricLine(start, "")
                1 -> LyricLine(start, "plain $start")
                2 -> LyricLine(start, "stated $start", sungUntilMs = start + random.nextLong(100, 9_000))
                3 -> timed(start, start + random.nextLong(300, 9_000))
                4 -> LyricLine(start, "lead $start", background = timed(start + random.nextLong(0, 1_000), start + random.nextLong(1_500, 8_000), "echo"))
                else -> LyricLine(start, "", sungUntilMs = start + 2_000L)
            }
        }
    }

    @Test
    fun theLinesBeingSungAndTheUppermostOfThemAreTheOldPanelsAtEveryPosition() {
        for (seed in 1..120) {
            val lines = song(Random(seed))
            val activity = LyricActivity(lines)
            for (position in -500L..42_000L step 173L) {
                val old = activeLyricRows(lines, position)
                val latest = activity.latest(position)
                val mine = lines.indices.filter { activity.isActive(it, latest, position) }
                assertEquals(old, mine, "rows, seed $seed at $position")
                assertEquals(old.firstOrNull() ?: -1, activity.firstActive(latest, position), "anchor, seed $seed at $position")
            }
        }
    }
}
