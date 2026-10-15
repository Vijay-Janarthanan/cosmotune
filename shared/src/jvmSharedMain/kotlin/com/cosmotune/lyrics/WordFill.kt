package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.GrowingWord
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord

/**
 * The maths of the word-by-word fill and of the words lifting as they are sung: which timed piece of a line holds the edge,
 * how far through it the edge is, and the answers the draw lambdas ask for on every frame.
 *
 * [LyricLine.revealedChars] and [LyricLine.isLifted] give the same numbers, but each builds an iterator (and `revealedChars`
 * searches the text for every word) per call. Here the line's own cached word spans are read with plain index loops, so a frame
 * of the fill allocates nothing; the tests hold these to the line's answers at every position.
 */
object WordFill {
    /**
     * The index in [spans] of the timed piece (a syllable, or a word nobody split) that holds the fractional character position
     * [chars], or -1 when it falls between two of them (in a space), or outside them all.
     */
    fun pieceAt(spans: List<IntRange>, chars: Float): Int {
        for (i in spans.indices) {
            val span = spans[i]
            if (chars >= span.first && chars < span.last + 1) return i
        }
        return -1
    }

    /** How far through [piece] the position [chars] is, 0 to 1: the edge crosses a piece at a steady rate, whatever letters it holds. */
    fun through(piece: IntRange, chars: Float): Float {
        val length = piece.last + 1 - piece.first
        if (length <= 0) return 1f
        return ((chars - piece.first) / length).coerceIn(0f, 1f)
    }

    /** Whether [piece] sits whole on the visual line that holds the characters [rowStart] until [rowEnd]. Cut across its width if so, letter by letter if it runs over a wrap. */
    fun sitsOnRow(piece: IntRange, rowStart: Int, rowEnd: Int): Boolean = piece.first >= rowStart && piece.last + 1 <= rowEnd

    /**
     * How many characters of [line] have been sung at [positionMs], as a fractional position: [LyricLine.revealedChars], read
     * off the cached word spans. A held word draws slowly and a rattled-off one snaps; the space between two words fills over the
     * pause between them.
     */
    fun revealedChars(line: LyricLine, positionMs: Long): Float {
        // A translation takes its timing from the line it translates: the line's own arithmetic, which is rare enough to leave alone.
        if (line.timingSource != null) return line.revealedChars(positionMs)
        val words = line.words
        if (words.isEmpty()) return if (positionMs >= line.timeMs) line.text.length.toFloat() else 0f
        val spans = line.wordSpans
        for (index in words.indices) {
            val word = words[index]
            val start = spans[index].first
            val end = spans[index].last + 1
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) return start + charsSungAt(word, positionMs)
            if (index + 1 < words.size) {
                val next = words[index + 1]
                if (positionMs < next.startMs) {
                    val gapStart = spans[index + 1].first
                    val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                    val through = (positionMs - word.endMs).toFloat() / pause
                    return end + through * (gapStart - end)
                }
            }
        }
        return line.text.length.toFloat()
    }

    /** [LyricWord.charsSungAt] with an index loop over the syllables. */
    private fun charsSungAt(word: LyricWord, positionMs: Long): Float {
        if (positionMs <= word.startMs) return 0f
        if (positionMs >= word.endMs) return word.text.length.toFloat()
        val syllables = word.syllables
        if (syllables.isEmpty()) {
            val span = (word.endMs - word.startMs).coerceAtLeast(1L)
            return (positionMs - word.startMs).toFloat() / span * word.text.length
        }
        for (i in syllables.indices) {
            val syllable = syllables[i]
            if (positionMs < syllable.startMs) return syllable.startChar.toFloat()
            if (positionMs < syllable.endMs) {
                val span = (syllable.endMs - syllable.startMs).coerceAtLeast(1L)
                val through = (positionMs - syllable.startMs).toFloat() / span
                return syllable.startChar + through * (syllable.endChar - syllable.startChar)
            }
        }
        return word.text.length.toFloat()
    }

    /**
     * Whether anything on [line] is off the floor at [positionMs] ([LyricLine.isLifted]): two comparisons for the lines that
     * are not being sung, which is every line but one on every frame.
     */
    fun isLifted(line: LyricLine, positionMs: Long): Boolean {
        val words = line.words
        if (words.isEmpty()) return false
        if (positionMs <= words[0].startMs) return false
        // The line's own settle (RISE_MS, 700) after the last word; a held word runs on past it, its last letter only starting as the word ends.
        return positionMs < words[words.size - 1].endMs + RISE_MS || isGrowing(line, positionMs)
    }

    private fun isGrowing(line: LyricLine, positionMs: Long): Boolean {
        val growing = line.growingWords
        for (i in growing.indices) {
            val word = growing[i]
            if (positionMs >= word.startMs && positionMs <= word.restsAtMs) return true
        }
        return false
    }

    /** One entry per word of [line]: the word's letter-by-letter treatment if it has earned one, else null. Built once per line; the frame only indexes it. */
    fun growers(line: LyricLine): Array<GrowingWord?> {
        val out = arrayOfNulls<GrowingWord>(line.words.size)
        val growing = line.growingWords
        for (i in growing.indices) {
            val word = growing[i]
            if (word.index in out.indices) out[word.index] = word
        }
        return out
    }

    private const val RISE_MS = 700f
}
