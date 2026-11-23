package com.cosmotune.podio

/**
 * Lines that start with a time: "00:00 Intro", "[1:02:03] Wrap up", "(12:30) - The big question". Used for chapter lists in show
 * notes and for plain-text transcripts. A time in the middle of a sentence is not a line start and never matches.
 *
 * The idea of reading chapter times out of show notes follows gpodder (GPL-3.0); this is written for Cosmotune's own types.
 */
internal object TimestampLines {
    data class Line(val startMs: Long, val text: String)

    private val LINE = Regex("^[\\s\\u2022*\\-]*[\\[(]?\\s*(\\d{1,3}(?::\\d{2}){1,2})(?:[.,]\\d{1,3})?\\s*[\\])]?\\s*[-\\u2013\\u2014:.)|]?\\s*(.+)$")

    fun parse(lines: List<String>): List<Line> = lines.take(5_000).mapNotNull { raw ->
        val match = LINE.matchEntire(raw.trim()) ?: return@mapNotNull null
        val ms = clock(match.groupValues[1]) ?: return@mapNotNull null
        val text = match.groupValues[2].trim().trimStart('-', '–', '—', ':', ' ').trim()
        if (text.isEmpty()) null else Line(ms, text)
    }

    /** "12:30" is minutes and seconds, "1:02:03" hours too; seconds (and minutes beside hours) stop at 59. */
    fun clock(value: String): Long? {
        val parts = value.split(':').map { it.toLongOrNull() ?: return null }
        val (h, m, s) = when (parts.size) {
            2 -> Triple(0L, parts[0], parts[1])
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> return null
        }
        if (s > 59 || parts.size == 3 && m > 59) return null
        return ((h * 3600 + m * 60 + s) * 1000).takeIf { it <= 604_800_000 }
    }
}
