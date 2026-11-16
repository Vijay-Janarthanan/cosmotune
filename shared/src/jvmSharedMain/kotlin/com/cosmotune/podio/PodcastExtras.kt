package com.cosmotune.podio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

@Serializable data class PodcastChapter(val startMs: Long, val title: String, val image: String? = null)
@Serializable data class PodcastTranscript(val url: String, val type: String = "application/x-subrip", val language: String = "")
data class PodcastCue(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)

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

    fun transcript(text: String): List<PodcastCue> {
        require(text.length <= 2_000_000)
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
