package com.cosmotune.podio.ui

import com.cosmotune.podio.PodioPlaybackPolicy
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four sections of Podio, in the design system's fixed slots (home, discover, following, library): the dock's tabs on a phone,
 * the rail's on a tablet, the sidebar's rows on the desktop.
 */
internal enum class PodioSection(val label: String) { RADIO("Radio"), PODCASTS("Podcasts"), EPISODES("Episodes"), LIBRARY("Library") }

/** What the Library section shows: everything, or one kind of thing. */
internal enum class SavedFilter(val label: String) { ALL("All"), STATIONS("Stations"), SHOWS("Shows"), DOWNLOADED("Downloaded") }

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

    /** "Played", "Resume at 1:25" or nothing, by what the library holds for the episode. */
    fun progress(completed: Boolean, positionMs: Long, durationMs: Long): String = when {
        completed -> "Played"
        positionMs <= 0 -> ""
        PodioPlaybackPolicy.isFinished(positionMs, durationMs) -> "Played"
        else -> "Resume at ${clock(positionMs)}"
    }

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

    /** Up to two initials for a station with no logo ("Hello FM" -> "HF"); "FM" when the name has no letters to take. */
    fun initials(name: String): String =
        name.split(' ', '-', '.', '_').filter(String::isNotBlank).take(2).joinToString("") { it.first().uppercase() }.ifBlank { "FM" }

    /** A station's own hue (0..359), the same for the same name, for the tile of a station with no logo. */
    fun hue(name: String): Int = (name.fold(7) { total, char -> total * 31 + char.code } and 0x7fffffff) % 360

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
     * The section a desktop sidebar row stands for: [tab] is its name ("Saved" is the Library's old name), [saved] the old
     * "Favourite stations" / "Following" rows, which open the Library.
     */
    fun section(tab: String, saved: Boolean): PodioSection = when {
        saved || tab == PodioSection.LIBRARY.label || tab == "Saved" -> PodioSection.LIBRARY
        tab == PodioSection.PODCASTS.label -> PodioSection.PODCASTS
        tab == PodioSection.EPISODES.label -> PodioSection.EPISODES
        else -> PodioSection.RADIO
    }

    /** The Library filter the old "Favourite stations" and "Following" rows open on. */
    fun savedFilter(tab: String, saved: Boolean): SavedFilter? = when {
        !saved -> null
        tab == PodioSection.RADIO.label -> SavedFilter.STATIONS
        tab == PodioSection.PODCASTS.label -> SavedFilter.SHOWS
        else -> null
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
