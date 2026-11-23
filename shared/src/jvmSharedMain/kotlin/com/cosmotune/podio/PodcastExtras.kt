package com.cosmotune.podio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

@Serializable data class PodcastChapter(val startMs: Long, val title: String, val image: String? = null)
@Serializable data class PodcastTranscript(val url: String, val type: String = "application/x-subrip", val language: String = "")
/** A line of a transcript. [startMs] is -1 for a transcript that carries no times: it can be read, not followed or tapped to seek. */
data class PodcastCue(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null) {
    val timed: Boolean get() = startMs >= 0
}

/** Publisher chapter/transcript formats; bounded, with no fetching during parsing. */
object PodcastExtras {
    fun chapters(text: String): List<PodcastChapter> {
        require(text.length <= 2_000_000)
        val root = Json.parseToJsonElement(text).jsonObject
        return (root["chapters"] as? JsonArray).orEmpty().take(5000).mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val seconds = (item["startTime"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..604800.0 } ?: return@mapNotNull null
            val title = (item["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(300).orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val image = (item["img"] as? JsonPrimitive)?.contentOrNull?.let(PodioCatalogue::httpUrl)
            PodcastChapter((seconds * 1000).toLong(), title, image)
        }.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /**
     * A transcript in any of the formats the Podcasting 2.0 namespace allows: SRT, WebVTT, JSON, HTML or plain text. The text itself
     * decides, so a feed that labels its file wrongly still reads; [type] only tells HTML from plain text. Timed formats come out
     * timed; HTML and plain text are timed when their lines start with a time, and read-only paragraphs when they do not.
     */
    fun transcript(text: String, type: String = ""): List<PodcastCue> {
        require(text.length <= 2_000_000)
        val body = text.removePrefix("\uFEFF")
        val head = body.trimStart()
        if (head.startsWith("{") || head.startsWith("[")) jsonTranscript(head)?.let { return it }
        return when {
            head.startsWith("{") -> emptyList()
            "-->" in body -> subtitleTranscript(body)
            type.contains("html", true) || head.startsWith("<") -> proseTranscript(htmlText(body, lineBreaks = false))
            else -> proseTranscript(body)
        }
    }

    /** Null when the text is not JSON at all (a plain transcript may start with a "[00:01]" time). */
    private fun jsonTranscript(text: String): List<PodcastCue>? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val segments = (if (root is JsonObject) (root["segments"] ?: root["cues"]) else root) as? JsonArray ?: return emptyList()
        val rows = segments.take(20_000).mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val start = (item["startTime"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..604800.0 } ?: return@mapNotNull null
            val end = (item["endTime"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= start && it <= 604800.0 }
            val words = (item["body"] as? JsonPrimitive)?.contentOrNull?.replace(Regex("[ \t\r\n]+"), " ")?.trim().orEmpty()
            if (words.isEmpty()) return@mapNotNull null
            val speaker = (item["speaker"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
            Triple((start * 1000).toLong(), end?.let { (it * 1000).toLong() }, PodcastCue(0, 0, words.take(10_000), speaker))
        }.sortedBy { it.first }
        return rows.mapIndexed { index, (start, end, cue) ->
            cue.copy(startMs = start, endMs = end ?: rows.getOrNull(index + 1)?.first ?: start)
        }
    }

    private fun proseTranscript(text: String): List<PodcastCue> {
        val lines = text.replace("\r\n", "\n").lines()
        val stamped = TimestampLines.parse(lines)
        if (stamped.size >= 2) {
            return stamped.mapIndexed { index, line ->
                PodcastCue(line.startMs, stamped.getOrNull(index + 1)?.startMs ?: line.startMs, line.text.take(10_000))
            }
        }
        return lines.joinToString("\n").split(Regex("\n[ \t]*\n")).take(20_000).mapNotNull { block ->
            block.replace(Regex("[ \t\r\n]+"), " ").trim().takeIf { it.isNotEmpty() }?.let { PodcastCue(-1, -1, it.take(10_000)) }
        }
    }

    /** Chapters from times at the start of lines in the show notes ("00:00 Intro", "(1:02:03) Wrap up"). */
    fun notesChapters(text: String): List<PodcastChapter> {
        val found = TimestampLines.parse(text.take(100_000).lines())
        if (found.size < 2) return emptyList()
        val rising = ArrayList<TimestampLines.Line>()
        for (line in found) if (rising.isEmpty() || line.startMs > rising.last().startMs) rising += line
        if (rising.size < 2) return emptyList()
        return rising.take(500).map { PodcastChapter(it.startMs, it.text.take(300)) }
    }

    /** The first of [candidates] (a feed's description, summary, content:encoded) that holds a list of chapter times. */
    fun notesChaptersOf(candidates: List<String>): List<PodcastChapter> {
        for (raw in candidates) {
            if (raw.isBlank()) continue
            val text = if ('<' in raw) htmlText(raw.take(200_000), lineBreaks = true) else raw
            notesChapters(text).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    /** A Podlove Simple Chapters time, "hh:mm:ss.mmm" (the hours, minutes and fraction are optional), or null. */
    fun npt(value: String): Long? {
        val m = Regex("^(?:([0-9]{1,3}):)?(?:([0-9]{1,2}):)?([0-9]{1,2})(?:[.]([0-9]{1,3}))?$").matchEntire(value.trim()) ?: return null
        val a = m.groupValues[1].toLongOrNull(); val b = m.groupValues[2].toLongOrNull(); val sec = m.groupValues[3].toLong()
        val hours = if (b != null) a ?: 0 else 0
        val minutes = b ?: a ?: 0
        if (sec > 59 || (b != null && minutes > 59)) return null
        val fraction = m.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return ((hours * 3600 + minutes * 60 + sec) * 1000 + fraction).takeIf { it <= 604_800_000 }
    }

    /**
     * Show notes or an HTML transcript as plain text with one line per block. Blocks are separated by a blank line; a line break
     * is a new line when [lineBreaks] (show notes) and a space when not (a paragraph that wraps).
     */
    internal fun htmlText(html: String, lineBreaks: Boolean): String {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        doc.select("br").forEach { it.before(if (lineBreaks) "\u0001" else " ") }
        doc.select("p,div,h1,h2,h3,h4,h5,h6,li,blockquote,tr,ul,ol").forEach { it.prependText("\u0001\u0001"); it.appendText("\u0001\u0001") }
        return doc.body().text().replace("\u0001", "\n").lines().joinToString("\n") { it.trim() }
    }

    private fun subtitleTranscript(text: String): List<PodcastCue> {
        val timestamp = Regex("(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
        fun time(value: String): Long? {
            val m = timestamp.find(value.trim()) ?: return null
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val minutes = m.groupValues[2].toLong(); val seconds = m.groupValues[3].toLong()
            if (minutes > 59 || seconds > 59) return null
            return ((h * 3600 + minutes * 60 + seconds) * 1000 + m.groupValues[4].toLong()).takeIf { it <= 604800000 }
        }
        return text.replace("\r\n", "\n").split(Regex("\n[ \\t]*\n")).take(20_000).mapNotNull { block ->
            val lines = block.lines(); val timing = lines.indexOfFirst { "-->" in it }
            if (timing < 0) return@mapNotNull null
            val sides = lines[timing].split("-->", limit = 2)
            val start = time(sides[0]) ?: return@mapNotNull null
            val end = time(sides[1])?.takeIf { it > start } ?: return@mapNotNull null
            val raw = lines.drop(timing + 1).joinToString(" ").trim()
            val voice = Regex("^<v(?:\\.[^ >]+)?[ \\t]+([^>]{1,80})>").find(raw)
            val clean = Jsoup.parse(raw).text().trim()
            val colon = clean.indexOf(": ")
            val speaker = voice?.groupValues?.get(1) ?: clean.substringBefore(':').takeIf { colon in 1..80 && !it.contains('/') }
            val words = if (voice == null && speaker != null) clean.substring(colon + 2).trim() else clean
            if (words.isBlank() || words.endsWith(':') && words.count { it == ':' } == 1) return@mapNotNull null
            PodcastCue(start, end, words.take(10_000), speaker)
        }.sortedBy { it.startMs }
    }
}
