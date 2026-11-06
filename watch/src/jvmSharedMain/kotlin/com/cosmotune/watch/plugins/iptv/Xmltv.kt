package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.model.EpgProgramme
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser
import java.io.InputStream
import java.io.InputStreamReader

data class XmltvChannel(val id: String, val names: List<String>, val iconUrl: String?)

/**
 * XMLTV guides, read as a stream (guides reach 100 MB): only programmes overlapping [fromMs, toMs) and, when [wanted] is
 * given, only those channels are kept; each `<programme>` is dropped from the tree as soon as it is read.
 */
object XmltvParser {
    class Result(val channels: List<XmltvChannel>, val programmes: List<EpgProgramme>)

    fun parse(input: InputStream, fromMs: Long, toMs: Long, wanted: Set<String>? = null, maxProgrammes: Int = 200_000): Result {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<EpgProgramme>()
        StreamParser(Parser.xmlParser()).use { parser ->
            parser.parse(InputStreamReader(input, Charsets.UTF_8), "")
            for (el in parser.stream()) {
                when (el.tagName()) {
                    "channel" -> {
                        val id = el.attr("id")
                        if (id.isNotBlank()) channels += XmltvChannel(id, el.getElementsByTag("display-name").map { it.text() }, el.getElementsByTag("icon").firstOrNull()?.attr("src")?.ifBlank { null })
                        el.remove()
                    }
                    "programme" -> {
                        val ch = el.attr("channel")
                        if (wanted == null || ch in wanted) {
                            val start = time(el.attr("start")); val stop = time(el.attr("stop"))
                            if (start != null && stop != null && stop > fromMs && start < toMs && programmes.size < maxProgrammes) {
                                programmes += EpgProgramme(ch, start, stop, el.getElementsByTag("title").firstOrNull()?.text().orEmpty().ifBlank { "Programme" },
                                    el.getElementsByTag("sub-title").firstOrNull()?.text()?.ifBlank { null },
                                    el.getElementsByTag("desc").firstOrNull()?.text()?.ifBlank { null },
                                    el.getElementsByTag("category").map { it.text() }.filter { it.isNotBlank() },
                                    el.getElementsByTag("episode-num").firstOrNull()?.text()?.ifBlank { null },
                                    el.getElementsByTag("icon").firstOrNull()?.attr("src")?.ifBlank { null },
                                    el.getElementsByTag("rating").firstOrNull()?.getElementsByTag("value")?.firstOrNull()?.text()?.ifBlank { null })
                            }
                        }
                        el.remove()
                    }
                }
            }
        }
        return Result(channels, programmes)
    }

    private val format = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+-]\\d{4})?")

    /** "20261106203000 +0530" to epoch ms; a time with no offset is UTC. */
    fun time(text: String): Long? {
        val m = format.find(text.trim()) ?: return null
        val g = m.groupValues
        val offset = g[7].takeIf { it.isNotEmpty() }?.let { o ->
            val sign = if (o[0] == '-') -1 else 1
            sign * (o.substring(1, 3).toInt() * 60 + o.substring(3, 5).toInt())
        } ?: 0
        val ldt = java.time.LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        return ldt.toInstant(java.time.ZoneOffset.ofTotalSeconds(offset * 60)).toEpochMilli()
    }
}
