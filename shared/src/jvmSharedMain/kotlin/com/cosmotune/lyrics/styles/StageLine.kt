package com.cosmotune.lyrics.styles

import com.cosmotune.lyrics.SungFraction
import com.cosmotune.lyrics.WordFill
import com.cosmotune.music.data.lyrics.LyricLine
import kotlin.math.min

/**
 * One line as the stage styles (everything but Orbit Read's list) read it: its words, each with the characters it covers and when
 * it is sung. A line with word timing keeps the provider's; a line stamped only at its start has its words spread over its slot,
 * a word's share in proportion to its letters, so a long word still takes longer than a short one.
 *
 * Built once per line as it comes up, never per frame; every question a frame asks is answered from the arrays without
 * allocating.
 */
class StageLine(val line: LyricLine, /** When the line's singing ends: its own end where it is known, else its slot's. */ val endMs: Long) {
    val text: String get() = line.text

    /** How many words the line has. */
    val count: Int

    /** Where word `i` sits in [text]: characters `charStart[i]` until `charEnd[i]`. */
    val charStart: IntArray
    val charEnd: IntArray

    /** When word `i` is sung. */
    val startMs: LongArray
    val wordEndMs: LongArray

    init {
        val words = line.words
        if (words.isNotEmpty()) {
            count = words.size
            charStart = IntArray(count)
            charEnd = IntArray(count)
            startMs = LongArray(count)
            wordEndMs = LongArray(count)
            val spans = line.wordSpans
            for (i in 0 until count) {
                charStart[i] = spans[i].first.coerceIn(0, text.length)
                charEnd[i] = (spans[i].last + 1).coerceIn(charStart[i], text.length)
                startMs[i] = words[i].startMs
                wordEndMs[i] = maxOf(words[i].startMs, words[i].endMs)
            }
        } else {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                while (i < text.length && text[i].isWhitespace()) i++
                if (i >= text.length) break
                val from = i
                while (i < text.length && !text[i].isWhitespace()) i++
                starts += from
                ends += i
            }
            count = starts.size
            charStart = IntArray(count) { starts[it] }
            charEnd = IntArray(count) { ends[it] }
            startMs = LongArray(count)
            wordEndMs = LongArray(count)
            val letters = ends.indices.sumOf { ends[it] - starts[it] }.coerceAtLeast(1)
            val span = (endMs - line.timeMs).coerceAtLeast(0L)
            var through = 0
            for (w in 0 until count) {
                startMs[w] = line.timeMs + span * through / letters
                through += charEnd[w] - charStart[w]
                wordEndMs[w] = line.timeMs + span * through / letters
            }
        }
    }

    /** When the first word starts and the last ends. */
    val firstMs: Long get() = if (count > 0) startMs[0] else line.timeMs
    val lastMs: Long get() = if (count > 0) maxOf(wordEndMs[count - 1], firstMs) else endMs

    /** How many words have started at [positionMs]: they are lit, in the styles that light a word at a time. */
    fun startedWords(positionMs: Long): Int {
        var n = 0
        while (n < count && startMs[n] <= positionMs) n++
        return n
    }

    /** How long ago word [index] started, in ms (negative before it). */
    fun sinceStart(index: Int, positionMs: Long): Long = positionMs - startMs[index]

    /** How far through the line the singing is, 0..1, by time: what the moon follows, so it moves on through a held note. */
    fun progress(positionMs: Long): Float {
        val from = firstMs
        val until = lastMs
        if (positionMs <= from) return 0f
        if (positionMs >= until) return 1f
        return (positionMs - from).toFloat() / (until - from).coerceAtLeast(1L)
    }

    /** How far through word [index] the singing is, 0..1, along its syllables where it has them. */
    fun wordSung(index: Int, positionMs: Long): Float {
        val words = line.words
        if (index < words.size) {
            val word = words[index]
            if (word.text.isEmpty()) return if (positionMs >= word.endMs) 1f else 0f
            return (word.charsSungAt(positionMs) / word.text.length).coerceIn(0f, 1f)
        }
        val from = startMs[index]
        val until = wordEndMs[index]
        if (positionMs <= from) return 0f
        if (positionMs >= until) return 1f
        return (positionMs - from).toFloat() / (until - from).coerceAtLeast(1L)
    }

    /** How many characters of [text] are sung at [positionMs], as a fractional position: the sweep's edge. */
    fun revealedChars(positionMs: Long): Float {
        if (line.isWordSynced || line.timingSource != null) return WordFill.revealedChars(line, positionMs)
        if (count == 0) return if (positionMs >= line.timeMs) text.length.toFloat() else 0f
        for (w in 0 until count) {
            if (positionMs < startMs[w]) return charStart[w].toFloat()
            if (positionMs < wordEndMs[w]) return charStart[w] + wordSung(w, positionMs) * (charEnd[w] - charStart[w])
        }
        return text.length.toFloat()
    }

    companion object {
        /** When line [index] of [lines] stops being sung: its own end where the provider gave one, else its slot (capped). */
        fun endOf(lines: List<LyricLine>, index: Int): Long {
            val line = lines[index]
            if (line.hasKnownEnd) return maxOf(line.endMs, line.timeMs)
            val next = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + SungFraction.LAST_LINE_MS)
            return min(next, line.timeMs + SungFraction.LINE_FILL_CAP_MS).coerceAtLeast(line.timeMs)
        }

        fun of(lines: List<LyricLine>, index: Int): StageLine = StageLine(lines[index], endOf(lines, index))
    }
}

/**
 * Where the song is among its lines, for a stage that shows one line at a time with the one before and the one after. Index
 * loops and no allocation: these run in derived states read every frame.
 */
object LyricCursor {
    /** The last line whose stamp has passed, or -1 before the first. */
    fun current(lines: List<LyricLine>, positionMs: Long): Int {
        var i = lines.size - 1
        while (i >= 0) {
            if (lines[i].timeMs <= positionMs) return i
            i--
        }
        return -1
    }

    /** The line being sung at [positionMs], or -1 in an instrumental (a blank line) and before the first. */
    fun sung(lines: List<LyricLine>, positionMs: Long): Int {
        val at = current(lines, positionMs)
        return if (at >= 0 && !lines[at].isGap && lines[at].text.isNotBlank()) at else -1
    }

    /** The nearest line after [from] with words in it, or -1. */
    fun nextSung(lines: List<LyricLine>, from: Int): Int {
        var i = from + 1
        while (i < lines.size) {
            if (!lines[i].isGap && lines[i].text.isNotBlank()) return i
            i++
        }
        return -1
    }

    /** The nearest line before [from] with words in it, or -1. */
    fun previousSung(lines: List<LyricLine>, from: Int): Int {
        var i = from - 1
        while (i >= 0) {
            if (!lines[i].isGap && lines[i].text.isNotBlank()) return i
            i--
        }
        return -1
    }

    /**
     * The line a stage is built around at [positionMs]: the one being sung, or in an instrumental (and before the first line) the
     * one that comes next, which then waits unlit. Changes once a line, so a stage keyed on it lays out once a line.
     */
    fun focus(lines: List<LyricLine>, positionMs: Long): Int {
        val at = current(lines, positionMs)
        if (at >= 0 && !lines[at].isGap && lines[at].text.isNotBlank()) return at
        return nextSung(lines, at)
    }
}

/**
 * The moon under the line being sung (Orbit Read, Pulse): where it is along the line, 0..1, by time. It follows the clock from
 * the first word's start to the last word's end, so it keeps moving through a held note where the highlight rests on one
 * syllable: the board's "the moon shows where the singer is". A line stamped only at its start runs over its slot.
 */
object LyricMoon {
    fun fraction(lines: List<LyricLine>, index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        if (line.isGap) return 0f
        val words = line.words
        val from: Long
        val until: Long
        if (words.isNotEmpty()) {
            from = words[0].startMs
            until = maxOf(words[words.size - 1].endMs, from + 1)
        } else {
            from = line.timeMs
            until = maxOf(StageLine.endOf(lines, index), from + 1)
        }
        if (positionMs <= from) return 0f
        if (positionMs >= until) return 1f
        return (positionMs - from).toFloat() / (until - from)
    }
}
