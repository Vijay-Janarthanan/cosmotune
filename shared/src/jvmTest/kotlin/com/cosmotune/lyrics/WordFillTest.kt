package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricSyllable
import com.cosmotune.music.data.lyrics.LyricWord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The maths of the word-by-word fill: which timed piece of a line holds the edge, how far through it the edge is, and the
 * per-frame answers the draw lambdas need, without an iterator or a boxed value on the way ([LyricLine.revealedChars] and
 * [LyricLine.isLifted] give the same numbers but build an iterator on each call).
 */
class WordFillTest {

    // ---- the piece that holds the edge --------------------------------------------------------------------------------

    private val spans = listOf(0 until 4, 5 until 10, 11 until 14)

    @Test
    fun theEdgeIsInsideThePieceThatHoldsIt() {
        assertEquals(0, WordFill.pieceAt(spans, 0f))
        assertEquals(0, WordFill.pieceAt(spans, 1.5f))
        assertEquals(0, WordFill.pieceAt(spans, 3.99f))
        assertEquals(1, WordFill.pieceAt(spans, 5f))
        assertEquals(1, WordFill.pieceAt(spans, 9.5f))
        assertEquals(2, WordFill.pieceAt(spans, 13.9f))
    }

    @Test
    fun theSpaceBetweenTwoPiecesBelongsToNeitherOfThem() {
        assertEquals(-1, WordFill.pieceAt(spans, 4f))
        assertEquals(-1, WordFill.pieceAt(spans, 4.5f))
        assertEquals(-1, WordFill.pieceAt(spans, 10.2f))
        assertEquals(-1, WordFill.pieceAt(spans, 14f))
        assertEquals(-1, WordFill.pieceAt(spans, -1f))
        assertEquals(-1, WordFill.pieceAt(emptyList(), 2f))
    }

    @Test
    fun theEdgeCrossesAPieceAtASteadyRate() {
        val piece = 5 until 10
        assertEquals(0f, WordFill.through(piece, 5f), 0f)
        assertEquals(0.5f, WordFill.through(piece, 7.5f), 1e-6f)
        assertEquals(1f, WordFill.through(piece, 10f), 1e-6f)
        assertEquals(0f, WordFill.through(piece, 3f), 0f, "never before the piece")
        assertEquals(1f, WordFill.through(piece, 12f), 0f, "never past it")
    }

    @Test
    fun aPieceThatSitsWholeOnARowIsCutOnItsOwnWidthAndOneBrokenOverAWrapIsCutLetterByLetter() {
        // the visual line holds characters 5 until 14
        assertTrue(WordFill.sitsOnRow(5 until 10, rowStart = 5, rowEnd = 14))
        assertTrue(WordFill.sitsOnRow(11 until 14, rowStart = 5, rowEnd = 14))
        assertFalse(WordFill.sitsOnRow(3 until 8, rowStart = 5, rowEnd = 14), "starts on the row above")
        assertFalse(WordFill.sitsOnRow(11 until 16, rowStart = 5, rowEnd = 14), "runs on to the row below")
    }

    // ---- the same numbers as the line's own, without the iterator ----------------------------------------------------------

    private fun lineOf(vararg words: LyricWord, text: String = words.joinToString(" ") { it.text }, timeMs: Long = words.first().startMs) =
        LyricLine(timeMs, text, words = words.toList())

    private val songs = listOf(
        lineOf(LyricWord(1_000, 1_400, "hold"), LyricWord(1_500, 1_900, "me"), LyricWord(2_000, 3_800, "close")),
        // a word that comes round again: each is found at its own place in the line
        lineOf(LyricWord(0, 300, "la"), LyricWord(300, 600, "la"), LyricWord(900, 1_300, "land")),
        // syllables, a pause between two of them
        lineOf(
            LyricWord(0, 1_000, "ba-by", syllables = listOf(LyricSyllable(0, 300, 0, 3), LyricSyllable(500, 1_000, 3, 5))),
            LyricWord(1_200, 1_900, "doll"),
        ),
        // more spaces than one between words, and punctuation
        lineOf(LyricWord(0, 500, "oh,"), LyricWord(700, 1_400, "oh!"), text = "oh,  oh!"),
        // a word the line text does not contain: the walk falls back to where the last one ended
        lineOf(LyricWord(0, 500, "one"), LyricWord(600, 1_000, "ghost"), text = "one two"),
        // no words: the line fills whole once its stamp has passed
        LyricLine(500L, "plain line"),
    )

    @Test
    fun theCharactersSungAreWhatTheLineSaysAtEveryPosition() {
        for (song in songs) {
            for (position in -200L..4_500L step 7L) {
                assertEquals(song.revealedChars(position), WordFill.revealedChars(song, position), 1e-4f, "${song.text} at $position")
            }
        }
    }

    @Test
    fun aTranslationTakesItsTimingFromTheLineItTranslates() {
        val original = songs.first()
        val translated = LyricLine(original.timeMs, "mantén me cerca", timingSource = original)
        for (position in 0L..4_000L step 50L) {
            assertEquals(translated.revealedChars(position), WordFill.revealedChars(translated, position), 1e-4f, "at $position")
        }
    }

    @Test
    fun theCharactersSungNeverRunBackwardsAsTheSongMovesOn() {
        // (not the line whose word is missing from its text: it runs one character past the end, and falls back, by design)
        for (song in songs.filter { it.text != "one two" }) {
            var last = -1f
            for (position in -200L..4_500L step 5L) {
                val now = WordFill.revealedChars(song, position)
                assertTrue(now >= last - 1e-3f, "${song.text}: $now after $last at $position")
                last = now
            }
        }
    }

    @Test
    fun aWordIsLiftedWhenTheLineSaysItIsAtEveryPosition() {
        for (seed in 1..40) {
            val random = Random(seed)
            val song = randomLine(random)
            for (position in -200L..9_000L step 31L) {
                assertEquals(song.isLifted(position), WordFill.isLifted(song, position), "seed $seed at $position")
            }
        }
    }

    @Test
    fun aLineWithNoWordsIsNeverLifted() {
        assertFalse(WordFill.isLifted(LyricLine(0L, "plain"), 100L))
    }

    @Test
    fun theHeldWordOfALineIsFoundByItsIndex() {
        // 'close' is held for 1.8 s: long enough to be run through letter by letter
        val line = songs.first()
        val held = WordFill.growers(line)
        assertEquals(line.words.size, held.size)
        assertEquals(null, held[0])
        assertEquals(null, held[1])
        assertEquals(2, held[2]?.index)
    }

    @Test
    fun aLineWithNoHeldWordHasNoGrowers() {
        val quick = lineOf(LyricWord(0, 200, "a"), LyricWord(250, 500, "b"))
        assertTrue(WordFill.growers(quick).all { it == null })
        assertTrue(WordFill.growers(LyricLine(0L, "plain")).isEmpty())
    }

    private fun randomLine(random: Random): LyricLine {
        var time = random.nextLong(0, 1_000)
        val words = List(random.nextInt(1, 7)) {
            val start = time + random.nextLong(0, 400)
            val end = start + random.nextLong(80, 2_600)
            time = end
            LyricWord(start, end, "w".repeat(random.nextInt(1, 8)))
        }
        return LyricLine(words.first().startMs, words.joinToString(" ") { it.text }, words = words)
    }
}
