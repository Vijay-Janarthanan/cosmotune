package com.cosmotune.podio.ui

import com.cosmotune.podio.PodioPlaybackPolicy
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The sections of Podio (board section C): Radio, Podcasts, the Globe, Following and Downloads. The desktop sidebar and a tablet's
 * rail carry all five; the phone's dock carries four, the Globe opening full screen from the Radio page.
 */
internal enum class PodioSection(val label: String) { RADIO("Radio"), PODCASTS("Podcasts"), GLOBE("Globe"), FOLLOWING("Following"), DOWNLOADS("Downloads") }

/**
 * The words and numbers Podio's pages print, kept apart from the composables so they can be tested: clocks, durations, publication
 * dates, an episode's one-line metadata and the text of a show note.
 */
internal object PodioFormat {
    /** A playhead: "4:05", or "1:02:09" from an hour on. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val hours = total / 3600
        val minutes = total / 60 % 60
        val seconds = (total % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
    }

    /** An episode's length in words: "45 min", "1 h 5 min", "2 h"; empty when it is not known. */
    fun duration(seconds: Long): String = when {
        seconds <= 0 -> ""
        seconds < 3600 -> "${(seconds / 60).coerceAtLeast(1)} min"
        seconds % 3600 / 60 == 0L -> "${seconds / 3600} h"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    }

    /** What is left of a started episode: "12 min left". */
    fun remaining(positionMs: Long, durationMs: Long): String {
        if (durationMs <= 0 || positionMs <= 0 || positionMs >= durationMs) return ""
        return "${((durationMs - positionMs) / 60_000).coerceAtLeast(1)} min left"
    }

    /**
     * A feed's publication date, short: "12 Oct" this year, "12 Oct 2019" before. RSS writes RFC 822/1123 dates, Atom and
     * YouTube write ISO 8601; anything else (a relative "3 days ago") is shown as the feed gives it, cut to 25 characters.
     */
    fun date(published: String, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val text = published.trim()
        if (text.isEmpty()) return ""
        val day = parseDate(text) ?: return text.take(25)
        val pattern = if (day.year == today.year) "d MMM" else "d MMM yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(day)
    }

    private fun parseDate(text: String): LocalDate? = parseInstant(text)?.atZone(java.time.ZoneOffset.UTC)?.toLocalDate()

    private fun parseInstant(text: String): java.time.Instant? =
        runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            // A zone name RFC 1123 does not know ("EST", "PDT") is read as UTC: the day is what matters here.
            ?: runCatching { ZonedDateTime.parse(text.replace(Regex("\\s+[A-Z]{3,4}$"), " +0000"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()

    /** When an episode was published, in epoch milliseconds, for putting the newest first; null when the feed's date is unreadable. */
    fun publishedAt(published: String): Long? = published.trim().takeIf(String::isNotEmpty)?.let(::parseInstant)?.toEpochMilli()

    /** How far the playhead is through a started (and not finished) episode, 0..1; 0 when nothing is known. */
    fun progressFraction(completed: Boolean, positionMs: Long, durationMs: Long): Float = when {
        completed || durationMs <= 0 || positionMs <= 0 -> 0f
        PodioPlaybackPolicy.isFinished(positionMs, durationMs) -> 0f
        else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** An episode's one line under its title: "12 Oct · 45 min · Resume at 12:03 · Downloaded", the blanks left out. */
    fun episodeMeta(vararg parts: String): String = parts.map(String::trim).filter(String::isNotEmpty).joinToString(" · ")

    /** A station's line under its name: the place, the language and the stream ("Chennai, India · tamil · MP3 128 kbps"). */
    fun stationMeta(place: String, language: String, codec: String, bitrate: Int): String =
        episodeMeta(place, language, episodeMeta(codec, if (bitrate > 0) "$bitrate kbps" else "").replace(" · ", " "))

    /** A show note as plain text: tags out, paragraphs and line breaks kept as new lines, the common entities decoded. */
    fun plainText(html: String, limit: Int = 4000): String {
        val text = html.take(limit * 4)
            .replace(Regex("(?i)<\\s*br\\s*/?>"), "\n")
            .replace(Regex("(?i)</\\s*(p|div|li|h[1-6])\\s*>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&#8217;", "’").replace("&#8211;", "–")
        return text.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .fold(mutableListOf<String>()) { kept, line -> if (line.isNotEmpty() || kept.lastOrNull()?.isNotEmpty() == true) kept += line; kept }
            .joinToString("\n").trim().take(limit)
    }

    /**
     * The section a desktop sidebar row stands for: [tab] is its name. The old names still land somewhere sensible: "Episodes",
     * "Library" and "Saved" (and the old [saved] rows) open Following, where the follows, the new episodes and the saved stations are.
     */
    fun section(tab: String, saved: Boolean): PodioSection = when {
        saved || tab == "Library" || tab == "Saved" || tab == "Episodes" -> PodioSection.FOLLOWING
        else -> PodioSection.entries.firstOrNull { it.label == tab } ?: PodioSection.RADIO
    }

    /** An episode's state in its row: "18 min left" (or "Resume at 1:25" with no length known), "Played", or "New" when untouched. */
    fun heard(completed: Boolean, positionMs: Long, durationMs: Long): String = when {
        completed || positionMs > 0 && PodioPlaybackPolicy.isFinished(positionMs, durationMs) -> "Played"
        positionMs <= 0 -> ""
        durationMs > 0 -> remaining(positionMs, durationMs)
        else -> "Resume at ${clock(positionMs)}"
    }

    /**
     * Where the stations sheet settles when a drag ends: the anchor nearest [height], unless the drag was a fling of more than
     * 1,200 px/s, which goes on to the next anchor up (a negative [velocity]) or down. [anchors] are heights in pixels.
     */
    fun snap(height: Float, velocity: Float, anchors: List<Float>): Float {
        val sorted = anchors.sorted()
        if (sorted.isEmpty()) return height
        if (velocity < -FLING) return sorted.firstOrNull { it > height + 1f } ?: sorted.last()
        if (velocity > FLING) return sorted.lastOrNull { it < height - 1f } ?: sorted.first()
        return sorted.minBy { kotlin.math.abs(it - height) }
    }

    private const val FLING = 1200f
}
