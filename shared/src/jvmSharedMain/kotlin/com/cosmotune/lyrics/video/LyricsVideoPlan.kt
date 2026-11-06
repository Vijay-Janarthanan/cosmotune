package com.cosmotune.lyrics.video

import com.cosmotune.music.data.lyrics.LyricLine

/**
 * What a lyrics video shows, frame by frame, before any pixel is drawn: a stretch of the song ([startMs] to [endMs]) and,
 * at each moment of it, the line being sung, the one before and the one after, and how much of the line has been sung
 * (word by word where the lyrics are word-timed, evenly across the line otherwise). Each app draws these frames with its
 * own 2D API and encodes them with its own encoder (MediaCodec on the phone, FFmpeg on the desktop).
 */
class LyricsVideoPlan(private val lines: List<LyricLine>, val startMs: Long, val endMs: Long) {

    data class Frame(val previous: String?, val current: String?, val next: String?, val sung: Float)

    val durationMs: Long get() = endMs - startMs

    /** The frame [atMs] into the video (not into the song). */
    fun frameAt(atMs: Long): Frame {
        val song = startMs + atMs
        val sung = lines.filter { !it.isGap && it.text.isNotBlank() }
        val index = sung.indexOfLast { it.timeMs <= song }
        if (index < 0) return Frame(null, null, sung.firstOrNull()?.text, 0f)
        val line = sung[index]
        val nextStart = sung.getOrNull(index + 1)?.timeMs
        val end = line.words.lastOrNull()?.endMs ?: line.sungUntilMs ?: nextStart ?: (line.timeMs + DEFAULT_LINE_MS)
        val fraction = if (line.isWordSynced && line.text.isNotEmpty()) {
            val spans = line.wordSpans
            var chars = 0f
            line.words.forEachIndexed { i, word ->
                val span = spans.getOrNull(i) ?: return@forEachIndexed
                val within = word.charsSungAt(song).coerceIn(0f, word.text.length.toFloat())
                if (within > 0f) chars = span.first + within
            }
            chars / line.text.length
        } else {
            ((song - line.timeMs).toFloat() / (end - line.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        return Frame(sung.getOrNull(index - 1)?.text, line.text, sung.getOrNull(index + 1)?.text, fraction.coerceIn(0f, 1f))
    }

    companion object {
        const val DEFAULT_LINE_MS = 4_000L
        const val MIN_MS = 6_000L
        const val MAX_MS = 30_000L
        /** Lead-in before the first chosen line, so it does not start mid-word. */
        const val LEAD_IN_MS = 800L

        /**
         * The stretch of [all] that holds the lines the listener chose in the share sheet ([chosen], as text), padded to at
         * least [MIN_MS] and cut to [MAX_MS]. With nothing chosen or nothing timed, the first timed lines. Null for lyrics
         * with no timing at all.
         */
        fun forSelection(all: List<LyricLine>, chosen: List<String>): LyricsVideoPlan? {
            val timed = all.filter { !it.isGap && it.text.isNotBlank() }
            if (timed.none { it.timeMs > 0 } && timed.size < 2) return null
            if (timed.all { it.timeMs == 0L }) return null
            val wanted = chosen.map(String::trim).filter(String::isNotEmpty)
            val first = wanted.firstOrNull()?.let { text -> timed.indexOfFirst { it.text.trim() == text } }?.takeIf { it >= 0 } ?: 0
            val last = wanted.lastOrNull()?.let { text ->
                (first until timed.size).firstOrNull { timed[it].text.trim() == text }
            } ?: first
            val start = (timed[first].timeMs - LEAD_IN_MS).coerceAtLeast(0)
            val lastLine = timed[last]
            val lastEnd = lastLine.words.lastOrNull()?.endMs ?: lastLine.sungUntilMs
                ?: timed.getOrNull(last + 1)?.timeMs ?: (lastLine.timeMs + DEFAULT_LINE_MS)
            val end = (lastEnd + 600).coerceIn(start + MIN_MS, start + MAX_MS)
            return LyricsVideoPlan(timed, start, end)
        }
    }
}
