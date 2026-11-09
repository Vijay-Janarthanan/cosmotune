package com.cosmotune.music.data.lyrics

import com.cosmotune.lyrics.lrcOffsetMs
import com.cosmotune.lyrics.withLrcOffset
/**
 * Enhanced ("A2") LRC — a normal LRC line with a stamp in front of each word:
 *
 * ```
 * [00:27.39]<00:27.39>I <00:27.54>been <00:27.74>tryna <00:28.07>call
 * ```
 *
 * This is what Musixmatch's rich sync becomes, and it is what SimpMusic
 * serves. Only starts are written down, so each word runs until the next one
 * begins, and the last word of a line until the next line does.
 */
object EnhancedLrc {

    private const val STAMP = """(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?"""
    private val LINE = Regex("""^\[$STAMP](.*)$""")
    private val WORD_STAMP = Regex("""<$STAMP>""")
    private val WORD = Regex("""<$STAMP>([^<]*)""")

    /** Empty when [lrc] carries no word stamps — the caller can then fall back. */
    fun parse(lrc: String): List<LyricLine> {
        val rows = lrc.lineSequence()
            .mapNotNull { line -> LINE.matchEntire(line.trim()) }
            .map { match ->
                val (body, singer) = splitLrcVocalPrefix(match.groupValues[4], WORD_STAMP)
                Row(
                    timeMs = stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3]),
                    words = WORD.findAll(body).toList(),
                    plain = body.trim(),
                    singer = singer,
                )
            }
            .sortedBy { it.timeMs }
            .toList()
        // Nothing word-stamped in here: this is an ordinary LRC file and the
        // caller is better served parsing it as one.
        if (rows.none { it.words.isNotEmpty() }) return emptyList()

        val offset = lrcOffsetMs(lrc)
        return rows.mapIndexedNotNull { index, row ->
            if (row.words.isEmpty()) {
                val text = decodeEntities(row.plain)
                return@mapIndexedNotNull if (text.isEmpty()) null else LyricLine(row.timeMs, text) to row.singer
            }
            // A word runs until the next one starts; the last runs until the
            // next line does. Without a next line — the closing word of the
            // song — give it a beat rather than zero, or its sweep never runs.
            val lineEnd = rows.getOrNull(index + 1)?.timeMs
                ?: (stamp(row.words.last()) + TAIL_MS)

            // Kept as written, spacing and all: a run with no space after it
            // is a syllable of a word that carries on into the next one.
            val runs = row.words.mapIndexed { i, match ->
                val wordStart = stamp(match)
                val wordEnd = row.words.getOrNull(i + 1)?.let { stamp(it) } ?: lineEnd
                TimedRun(wordStart, wordEnd, decodeEntities(match.groupValues[4]))
            }
            val words = wordsFromRuns(runs)
            if (words.isEmpty()) return@mapIndexedNotNull null
            LyricLine(
                timeMs = minOf(row.timeMs, words.first().startMs),
                text = words.joinToString(" ") { it.text },
                words = words,
            ) to row.singer
        }.withLrcVocalAlignments().map { it.withLrcOffset(offset) }.withInstrumentalGaps().withExplicitLrcBackgrounds(lrc)
    }

    private class Row(val timeMs: Long, val words: List<MatchResult>, val plain: String, val singer: String?)

    private fun stamp(match: MatchResult): Long =
        stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3])

    private fun stamp(minutes: String, seconds: String, fraction: String): Long {
        val fractionMs = fraction.padEnd(3, '0').toLong()
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }

    /**
     * SimpMusic serves its rich sync HTML-escaped, so an apostrophe arrives as
     * `&#x27;` and would be sung literally. Metrolist shipped that bug; this
     * is the fix.
     */
    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return text
            .replace(Regex("&#x([0-9a-fA-F]+);")) { decodeCodePoint(it, 16) }
            .replace(Regex("&#(\\d+);")) { decodeCodePoint(it, 10) }
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            // Last, so "&amp;#x27;" doesn't decode twice into an apostrophe.
            .replace("&amp;", "&")
    }

    private fun decodeCodePoint(entity: MatchResult, radix: Int): String {
        val point = entity.groupValues[1].toIntOrNull(radix) ?: return entity.value
        if (!Character.isValidCodePoint(point) || point in 0xD800..0xDFFF) return entity.value
        return String(Character.toChars(point))
    }

    private const val TAIL_MS = 800L
}
