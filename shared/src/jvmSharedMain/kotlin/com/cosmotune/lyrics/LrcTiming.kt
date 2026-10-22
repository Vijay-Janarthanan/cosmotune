package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricLine

/** LRC's positive offset advances lyrics; it is subtracted from every timestamp. */
internal fun lrcOffsetMs(document: String): Long = OFFSET.findAll(document)
    .mapNotNull { it.groupValues[1].toLongOrNull() }.lastOrNull() ?: 0L

internal fun LyricLine.withLrcOffset(offsetMs: Long): LyricLine {
    if (offsetMs == 0L) return this
    fun shift(time: Long): Long = when {
        offsetMs > 0 -> if (time < offsetMs) 0L else time - offsetMs
        time > Long.MAX_VALUE + offsetMs -> Long.MAX_VALUE
        else -> time - offsetMs
    }
    return copy(
        timeMs = shift(timeMs),
        sungUntilMs = sungUntilMs?.let(::shift),
        words = words.map { word ->
            word.copy(startMs = shift(word.startMs), endMs = shift(word.endMs),
                syllables = word.syllables.map { it.copy(startMs = shift(it.startMs), endMs = shift(it.endMs)) })
        },
        background = background?.withLrcOffset(offsetMs),
        timingSource = timingSource?.withLrcOffset(offsetMs),
    )
}

private val OFFSET = Regex("""^\s*\[offset\s*:\s*([+-]?\d+)\s*]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
