package com.cosmotune.music.data.lyrics

/**
 * Lyrics being written or timed by hand, the model under the player's lyrics editor.
 *
 * The listener types (or pastes) the words, then plays the song and taps as each line, or each word, is sung: [tap] stamps the
 * line or word under the cursor and moves it on, [undo] takes the last stamp back. A line can be marked as the second voice of a
 * duet ([toggleSecondVoice]), which the player draws on the other side. [toLines] turns the draft into the lines the player
 * shows; a line still without a stamp is placed between its stamped neighbours, so a half-timed draft still scrolls in order.
 */
data class LyricsDraft(
    val lines: List<Line>,
    val mode: Mode = Mode.LINES,
    /** The line the next tap stamps. */
    val line: Int = 0,
    /** In [Mode.WORDS], the word of [line] the next tap stamps. */
    val word: Int = 0,
) {
    enum class Mode { LINES, WORDS }

    /** One line: its words, when it starts, when each word starts (by hand, in [Mode.WORDS]), and which voice sings it. */
    data class Line(
        val text: String,
        val startMs: Long? = null,
        val wordStartsMs: List<Long?> = emptyList(),
        val secondVoice: Boolean = false,
    ) {
        val words: List<String> get() = text.split(WHITESPACE).filter { it.isNotEmpty() }
    }

    /** Whether every line has a stamp: the draft is fully timed. */
    val timed: Boolean get() = lines.isNotEmpty() && lines.all { it.startMs != null }

    /** Whether any line has a stamp. */
    val started: Boolean get() = lines.any { it.startMs != null }

    /** Whether the cursor has gone past the last line: everything has been tapped. */
    val finished: Boolean get() = line >= lines.size

    /** The words as one text, a line per line: what the words step shows for editing. */
    val text: String get() = lines.joinToString("\n") { it.text }

    /** Moves the cursor to [index] (a tap on a line in the list, to time again from there). */
    fun moveTo(index: Int): LyricsDraft = copy(line = index.coerceIn(0, lines.size), word = 0)

    fun withMode(mode: Mode): LyricsDraft = copy(mode = mode, word = 0)

    /** Stamps the line (or word) under the cursor at [atMs] and moves on. Stamps never run backwards: an early tap is moved up. */
    fun tap(atMs: Long): LyricsDraft {
        if (finished) return this
        val previous = lines.take(line).mapNotNull { l -> (l.wordStartsMs.filterNotNull() + listOfNotNull(l.startMs)).maxOrNull() }.maxOrNull() ?: 0L
        val current = lines[line]
        return when (mode) {
            Mode.LINES -> {
                val at = atMs.coerceAtLeast(previous).coerceAtLeast(0)
                copy(lines = lines.replace(line, current.copy(startMs = at, wordStartsMs = emptyList())), line = line + 1, word = 0)
            }
            Mode.WORDS -> {
                val count = current.words.size
                if (count == 0) {
                    val at = atMs.coerceAtLeast(previous).coerceAtLeast(0)
                    return copy(lines = lines.replace(line, current.copy(startMs = at, wordStartsMs = emptyList())), line = line + 1, word = 0)
                }
                val starts = (current.wordStartsMs + List(count) { null }).take(count).toMutableList()
                val before = starts.take(word).filterNotNull().maxOrNull() ?: previous
                val at = atMs.coerceAtLeast(before).coerceAtLeast(0)
                starts[word] = at
                if (word == 0) for (i in 1 until count) starts[i] = null
                val updated = current.copy(startMs = if (word == 0) at else current.startMs ?: at, wordStartsMs = starts)
                val next = word + 1
                if (next >= count) copy(lines = lines.replace(line, updated), line = line + 1, word = 0)
                else copy(lines = lines.replace(line, updated), word = next)
            }
        }
    }

    /** Takes the last stamp back and puts the cursor on it again. */
    fun undo(): LyricsDraft {
        if (mode == Mode.WORDS && !finished && word > 0) {
            val current = lines[line]
            val starts = current.wordStartsMs.toMutableList().also { if (word - 1 in it.indices) it[word - 1] = null }
            val cleared = if (word - 1 == 0) current.copy(startMs = null, wordStartsMs = emptyList()) else current.copy(wordStartsMs = starts)
            return copy(lines = lines.replace(line, cleared), word = word - 1)
        }
        if (line == 0) return this
        val back = line - 1
        val target = lines[back]
        return when {
            mode == Mode.WORDS && target.words.size > 1 && target.wordStartsMs.count { it != null } > 1 -> {
                val last = target.words.size - 1
                val starts = (target.wordStartsMs + List(target.words.size) { null }).take(target.words.size).toMutableList()
                starts[last] = null
                copy(lines = lines.replace(back, target.copy(wordStartsMs = starts)), line = back, word = last)
            }
            else -> copy(lines = lines.replace(back, target.copy(startMs = null, wordStartsMs = emptyList())), line = back, word = 0)
        }
    }

    fun toggleSecondVoice(index: Int = line.coerceAtMost(lines.size - 1)): LyricsDraft {
        if (index !in lines.indices) return this
        return copy(lines = lines.replace(index, lines[index].copy(secondVoice = !lines[index].secondVoice)))
    }

    /** New words, keeping the stamps of the lines that did not change (matched by position and text). */
    fun withText(text: String): LyricsDraft {
        val fresh = fromText(text).lines
        val merged = fresh.mapIndexed { index, line ->
            val old = lines.getOrNull(index)
            if (old != null && old.text == line.text) old else line
        }
        return copy(lines = merged, line = line.coerceAtMost(merged.size), word = 0)
    }

    /**
     * The lines the player shows. Untimed lines get a time between their stamped neighbours (or a few seconds after the last
     * stamp); a draft with no stamp at all is plain lyrics, every line at zero. A word stamped by hand lasts until the next word,
     * the last one until the next line starts (at most [MAX_LAST_WORD_MS]); [durationMs] closes the song's last line.
     */
    fun toLines(durationMs: Long = 0L): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()
        if (!started) return lines.map { LyricLine(0L, it.text, alignment = alignmentOf(it)) }
        val starts = filledStarts()
        return lines.mapIndexed { index, line ->
            val start = starts[index]
            val nextStart = starts.getOrNull(index + 1) ?: if (durationMs > start) durationMs else start + MAX_LAST_WORD_MS
            val words = line.words
            val stamped = (line.wordStartsMs + List(words.size) { null }).take(words.size)
            val timedWords = if (words.isNotEmpty() && stamped.first() != null) {
                val filled = fillWordStarts(stamped, start, nextStart)
                words.mapIndexed { w, text ->
                    val wordStart = filled[w]
                    val wordEnd = filled.getOrNull(w + 1) ?: minOf(nextStart, wordStart + MAX_LAST_WORD_MS)
                    LyricWord(wordStart, wordEnd.coerceAtLeast(wordStart + 1), text)
                }
            } else emptyList()
            LyricLine(
                timeMs = start,
                text = line.text,
                words = timedWords,
                sungUntilMs = timedWords.lastOrNull()?.endMs,
                alignment = alignmentOf(line),
            )
        }
    }

    private fun alignmentOf(line: Line) = if (line.secondVoice) LyricAlignment.End else LyricAlignment.Start

    private fun filledStarts(): List<Long> {
        val raw = lines.map { it.startMs }
        val out = LongArray(raw.size)
        var i = 0
        while (i < raw.size) {
            val known = raw[i]
            if (known != null) { out[i] = known; i++; continue }
            val prevIndex = i - 1
            val prev = if (prevIndex >= 0) out[prevIndex] else 0L
            var j = i
            while (j < raw.size && raw[j] == null) j++
            val next = raw.getOrNull(j)
            val gaps = j - prevIndex
            for (k in i until j) {
                out[k] = if (next != null) prev + (next - prev) * (k - prevIndex) / gaps else prev + UNTIMED_STEP_MS * (k - prevIndex)
            }
            i = j
        }
        return out.toList()
    }

    private fun fillWordStarts(stamped: List<Long?>, lineStart: Long, nextLine: Long): List<Long> {
        val out = LongArray(stamped.size)
        var i = 0
        while (i < stamped.size) {
            val known = stamped[i]
            if (known != null) { out[i] = known; i++; continue }
            val prev = if (i > 0) out[i - 1] else lineStart
            var j = i
            while (j < stamped.size && stamped[j] == null) j++
            val next = stamped.getOrNull(j) ?: nextLine.coerceAtLeast(prev + 1)
            val gaps = j - i + 1
            for (k in i until j) out[k] = prev + (next - prev) * (k - i + 1) / gaps
            i = j
        }
        return out.toList()
    }

    companion object {
        /** How long the last word of a line is held when nothing says when it ends. */
        const val MAX_LAST_WORD_MS = 4_000L

        /** Where an untimed line after the last stamp goes: this far after the line before it. */
        const val UNTIMED_STEP_MS = 3_000L

        private val WHITESPACE = Regex("\\s+")

        /** A draft of [text]: one line per non-empty line, nothing timed. */
        fun fromText(text: String): LyricsDraft =
            LyricsDraft(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Line(it) })

        /**
         * A draft of lyrics the player already has, to correct rather than start over: their words, and their stamps when they
         * are timed (word stamps too, where the words still split the same way). Gaps (instrumental breaks) are left out.
         */
        fun fromLines(lines: List<LyricLine>): LyricsDraft {
            val sung = lines.filter { !it.isGap }
            val timed = sung.count { it.timeMs > 0 } >= 2
            val mode = if (sung.any { it.isWordSynced }) Mode.WORDS else Mode.LINES
            val draftLines = sung.map { line ->
                val text = line.text.trim()
                val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
                val wordStarts = if (timed && line.words.size == words.size) line.words.map { it.startMs } else emptyList()
                Line(
                    text = text,
                    startMs = if (timed) line.timeMs else null,
                    wordStartsMs = wordStarts,
                    secondVoice = line.alignment == LyricAlignment.End,
                )
            }
            val draft = LyricsDraft(draftLines, mode)
            return if (draft.timed) draft.copy(line = draftLines.size) else draft
        }
    }
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
