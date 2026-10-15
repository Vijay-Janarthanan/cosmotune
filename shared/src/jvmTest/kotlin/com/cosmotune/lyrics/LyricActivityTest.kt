package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which lines are being sung at a moment, for a song whose voices overlap (a duet's call and answer). [LyricActivity] gives
 * what the old panel's `activeLyricRows` gives, without building a list on every frame.
 */
class LyricActivityTest {

    /** The old panel's rule, written out as it was (`LyricFocus.kt`), to hold the new one to. */
    private fun reference(lines: List<LyricLine>, positionMs: Long): List<Int> {
        val latest = lines.indexOfLast { it.timeMs <= positionMs }
        if (latest < 0) return emptyList()
        return (0..latest).filter { index ->
            val line = lines[index]
            index == latest || (!line.isGap &&
                (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
                line.timeMs <= positionMs && positionMs < line.endMs)
        }
    }

    private fun timed(start: Long, end: Long, text: String = "line at $start") =
        LyricLine(start, text, words = listOf(LyricWord(start, end, text)))

    /** A duet: the second singer comes in while the first is still holding the last word. */
    private val duet = listOf(
        timed(1_000L, 6_000L),    // 0: sung until 6 000
        timed(3_000L, 4_000L),    // 1: answers over it
        timed(7_000L, 9_000L),    // 2
        LyricLine(10_000L, ""),   // 3: a break
        timed(12_000L, 13_000L),  // 4
    )

    @Test
    fun theLatestLineIsAlwaysActive() {
        val activity = LyricActivity(duet)
        assertEquals(-1, activity.latest(500L))
        assertEquals(0, activity.latest(1_000L))
        assertEquals(1, activity.latest(3_500L))
        assertTrue(activity.isActive(1, 1, 3_500L))
        assertTrue(activity.isActive(3, 3, 11_000L), "a break is the line being sung while it runs")
    }

    @Test
    fun anEarlierLineIsStillActiveUntilItsOwnEnd() {
        val activity = LyricActivity(duet)
        // at 3 500 the answer has begun but line 0 runs until 6 000
        assertTrue(activity.isActive(0, 1, 3_500L))
        assertTrue(activity.isActive(0, 1, 5_999L))
        assertEquals(1, activity.latest(5_999L))
        // line 1 ended at 4 000 but is the latest until line 2 starts at 7 000
        assertTrue(activity.isActive(1, 1, 5_999L))
        // 6 000: line 0 is over; line 1 is the latest line, so it is the one left
        assertTrue(!activity.isActive(0, 1, 6_000L))
        assertTrue(activity.isActive(1, 1, 6_000L))
    }

    @Test
    fun theUppermostUnfinishedLineOwnsTheScrollAnchor() {
        val activity = LyricActivity(duet)
        assertEquals(-1, activity.firstActive(-1, 500L))
        assertEquals(0, activity.firstActive(0, 1_500L))
        assertEquals(0, activity.firstActive(1, 3_500L), "line 0 is still being sung")
        assertEquals(1, activity.firstActive(1, 6_500L), "line 0 is done")
        assertEquals(2, activity.firstActive(2, 8_000L))
        assertEquals(3, activity.firstActive(3, 11_000L))
    }

    @Test
    fun aBreakIsNeverKeptActiveByItsOwnEndOnceTheNextLineHasBegun() {
        val withBreak = listOf(timed(1_000L, 2_000L), LyricLine(2_500L, "", sungUntilMs = 9_000L), timed(5_000L, 6_000L))
        val activity = LyricActivity(withBreak)
        assertTrue(!activity.isActive(1, 2, 5_500L), "a gap is not an overlapped vocal")
    }

    @Test
    fun aLineStampedOnlyAtItsStartHasNoEndToOverlapWith() {
        val plain = listOf(LyricLine(1_000L, "one"), LyricLine(5_000L, "two"), LyricLine(9_000L, "three"))
        val activity = LyricActivity(plain)
        for (position in 0L..12_000L step 100L) {
            val latest = activity.latest(position)
            for (index in plain.indices) assertEquals(index == latest, activity.isActive(index, latest, position), "line $index at $position")
        }
    }

    @Test
    fun aStatedEndCountsAsAKnownEnd() {
        val stated = listOf(LyricLine(1_000L, "one", sungUntilMs = 8_000L), LyricLine(3_000L, "two"))
        val activity = LyricActivity(stated)
        assertTrue(activity.isActive(0, 1, 5_000L))
        assertTrue(!activity.isActive(0, 1, 8_000L))
    }

    @Test
    fun anEndOnTheBackgroundVoiceIsAnEndToo() {
        val answering = listOf(
            LyricLine(1_000L, "lead", background = timed(1_500L, 7_000L, "answer")),
            LyricLine(3_000L, "next"),
        )
        val activity = LyricActivity(answering)
        assertTrue(activity.isActive(0, 1, 6_000L), "the answering vocal is still this line being sung")
    }

    // ---- which lines can be kept being sung by a later one ---------------------------------------------------------------------

    @Test
    fun aLineThatRunsPastALaterStampMayOverlapAndOneThatDoesNotCannot() {
        val activity = LyricActivity(duet)
        assertTrue(activity.mayOverlap(0), "runs to 6 000, the answer is stamped at 3 000")
        assertTrue(!activity.mayOverlap(1), "ends at 4 000, before anything after it begins")
        assertTrue(!activity.mayOverlap(2))
        assertTrue(!activity.mayOverlap(3), "a break")
        assertTrue(!activity.mayOverlap(4), "the last line is only ever active as the latest")
        assertTrue(!activity.mayOverlap(-1) && !activity.mayOverlap(99))
    }

    @Test
    fun aLineThatCannotOverlapIsActiveOnlyWhileItIsTheLatestSoItNeverNeedsTheClock() {
        for (seed in 1..60) {
            val lines = randomSong(Random(seed))
            val activity = LyricActivity(lines)
            for (position in -500L..40_000L step 211L) {
                val latest = activity.latest(position)
                for (index in lines.indices) {
                    if (!activity.mayOverlap(index)) {
                        assertEquals(index == latest, activity.isActive(index, latest, position), "line $index, seed $seed at $position")
                    }
                }
            }
        }
    }

    // ---- the same answer as the old rule, everywhere -----------------------------------------------------------------------

    @Test
    fun itAnswersWhatTheOldRuleAnswersAtEveryPosition() {
        for (seed in 1..60) {
            val lines = randomSong(Random(seed))
            val activity = LyricActivity(lines)
            for (position in -500L..40_000L step 137L) {
                val expected = reference(lines, position)
                val latest = activity.latest(position)
                assertEquals(lines.indexOfLast { it.timeMs <= position }, latest, "latest, seed $seed at $position")
                val got = lines.indices.filter { activity.isActive(it, latest, position) }
                assertEquals(expected, got, "active rows, seed $seed at $position")
                assertEquals(expected.firstOrNull() ?: -1, activity.firstActive(latest, position), "first, seed $seed at $position")
            }
        }
    }

    private fun randomSong(random: Random): List<LyricLine> {
        val count = random.nextInt(0, 14)
        val sorted = random.nextBoolean()
        var time = random.nextLong(0, 3_000)
        return List(count) {
            time = if (sorted) time + random.nextLong(200, 4_000) else random.nextLong(0, 38_000)
            val start = time
            when (random.nextInt(6)) {
                0 -> LyricLine(start, "")
                1 -> LyricLine(start, "plain $start")
                2 -> LyricLine(start, "stated $start", sungUntilMs = start + random.nextLong(100, 9_000))
                3 -> timed(start, start + random.nextLong(300, 9_000))
                4 -> LyricLine(
                    start, "lead $start",
                    background = timed(start + random.nextLong(0, 1_000), start + random.nextLong(1_500, 8_000), "echo"),
                )
                else -> LyricLine(start, "gap-ish", sungUntilMs = start + 2_000L)
            }
        }
    }
}
