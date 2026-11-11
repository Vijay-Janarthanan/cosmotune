package com.cosmotune.music.data.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How the race settles on one answer. Invented lines only. */
class LyricsPickTest {

    private val plainLines = listOf(LyricLine(0, "Tin roof"), LyricLine(0, "Paper boat"), LyricLine(0, "Open gate"))
    private val timedLines = listOf(LyricLine(1_000, "Tin roof"), LyricLine(4_000, "Paper boat"), LyricLine(8_000, "Open gate"))
    private val wordLines = listOf(
        LyricLine(1_000, "Tin roof", words = listOf(LyricWord(1_000, 1_400, "Tin "), LyricWord(1_400, 2_000, "roof"))),
        LyricLine(4_000, "Paper boat", words = listOf(LyricWord(4_000, 4_600, "Paper "), LyricWord(4_600, 5_000, "boat"))),
    )

    private fun answer(source: LyricsSource, lines: List<LyricLine>?): Pair<LyricsSource, Deferred<LyricsRepository.Result?>> =
        source to CompletableDeferred(lines?.let { LyricsRepository.Result(source, it) })

    private fun pick(prioritize: Boolean, durationMs: Long = 0L, vararg answers: Pair<LyricsSource, Deferred<LyricsRepository.Result?>>) =
        runBlocking { LyricsRepository.pick(answers.toList(), prioritize, durationMs) }

    @Test
    fun `a timed answer beats a plain one that sits higher in the order`() {
        for (prioritize in listOf(false, true)) {
            val won = pick(
                prioritize, 0L,
                answer(LyricsSource.YOUTUBE_MUSIC, plainLines),
                answer(LyricsSource.LRCLIB, timedLines),
            )
            assertEquals(LyricsSource.LRCLIB, won?.source, "prioritize=$prioritize")
        }
    }

    @Test
    fun `plain text is still the answer when nothing is timed`() {
        val won = pick(false, 0L, answer(LyricsSource.LRC_RED, null), answer(LyricsSource.YOUTUBE_MUSIC, plainLines))
        assertEquals(LyricsSource.YOUTUBE_MUSIC, won?.source)
    }

    @Test
    fun `the first timed answer in order wins unless words are wanted`() {
        val won = pick(
            false, 0L,
            answer(LyricsSource.YOUTUBE_MUSIC, timedLines),
            answer(LyricsSource.LRCLIB, wordLines),
        )
        assertEquals(LyricsSource.YOUTUBE_MUSIC, won?.source)
        val held = pick(
            true, 0L,
            answer(LyricsSource.YOUTUBE_MUSIC, timedLines),
            answer(LyricsSource.LRCLIB, wordLines),
        )
        assertEquals(LyricsSource.LRCLIB, held?.source)
    }

    @Test
    fun `timing stamped at zero or past the end of the track counts as plain`() {
        val allAtZero = wordLines.map { it.copy(timeMs = 0) }
        assertEquals(LyricsRepository.Timing.NONE, LyricsRepository.timing(allAtZero, 0L))
        assertEquals(LyricsRepository.Timing.NONE, LyricsRepository.timing(timedLines, durationMs = 2_000L))
        assertEquals(LyricsRepository.Timing.LINE, LyricsRepository.timing(timedLines, durationMs = 9_000L))
        assertEquals(LyricsRepository.Timing.WORD, LyricsRepository.timing(wordLines, durationMs = 0L))

        val won = pick(
            false, 0L,
            answer(LyricsSource.BETTER_LYRICS_PORTATO, allAtZero),
            answer(LyricsSource.YOUTUBE_MUSIC, timedLines),
        )
        assertEquals(LyricsSource.YOUTUBE_MUSIC, won?.source)
    }

    @Test
    fun `a single stray line is not lyrics`() {
        assertNull(pick(false, 0L, answer(LyricsSource.BETTER_LYRICS_PORTATO, listOf(LyricLine(0, "Hum")))))
    }

    @Test
    fun `a failed source is passed over`() {
        val failed = LyricsSource.LRC_RED to CompletableDeferred<LyricsRepository.Result?>().apply {
            completeExceptionally(IllegalStateException("down"))
        }
        val won = pick(false, 0L, failed, answer(LyricsSource.LRCLIB, timedLines))
        assertEquals(LyricsSource.LRCLIB, won?.source)
    }
}
