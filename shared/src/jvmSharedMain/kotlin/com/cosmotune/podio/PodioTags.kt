package com.cosmotune.podio

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Tags for followed shows (AntennaPod's tags, Pocket Casts' folders): a show can carry several ("News", "Tamil"); Following's Shows
 * grid filters by one. In OPML a tag is a folder: an outline holding the shows' outlines, which other podcast apps read as folders.
 */
object PodioTags {
    const val MAX_PER_SHOW = 8

    /** A tag as it is kept: trimmed, one space between words, at most 30 characters. */
    fun clean(tag: String): String = tag.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ").take(30)

    /** [saved] with [showId]'s tags set to [tags]; none removes its entry. */
    fun set(saved: PodioSaved, showId: String, tags: Collection<String>): PodioSaved {
        val clean = tags.map(::clean).filter(String::isNotEmpty).distinctBy { it.lowercase() }.take(MAX_PER_SHOW).toSet()
        return saved.copy(showTags = if (clean.isEmpty()) saved.showTags - showId else saved.showTags + (showId to clean))
    }

    /** Every tag in use among the followed shows, A to Z. */
    fun all(saved: PodioSaved): List<String> = saved.shows.flatMap { saved.showTags[it.id].orEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** The followed shows that carry [tag]. */
    fun shows(saved: PodioSaved, tag: String): List<PodcastShow> = saved.shows.filter { show -> saved.showTags[show.id].orEmpty().any { it.equals(tag, ignoreCase = true) } }

    /** The folders an OPML document puts its feeds in: each feed's address with the titles of the outlines around it. */
    fun foldersOf(xml: String): Map<String, Set<String>> = runCatching {
        require(!xml.contains("<!DOCTYPE", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val document = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(xml.removePrefix("﻿"))))
        val outlines = document.getElementsByTagName("outline")
        (0 until outlines.length).mapNotNull { index ->
            val outline = outlines.item(index) as? Element ?: return@mapNotNull null
            val url = PodioCatalogue.httpUrl(outline.getAttribute("xmlUrl").ifBlank { outline.getAttribute("xmlurl") }) ?: return@mapNotNull null
            val folders = generateSequence(outline.parentNode as? Element) { it.parentNode as? Element }
                .filter { it.tagName.equals("outline", true) && it.getAttribute("xmlUrl").isBlank() }
                .map { clean(it.getAttribute("title").ifBlank { it.getAttribute("text") }) }.filter(String::isNotEmpty).toSet()
            (url.trimEnd('/') to folders).takeIf { folders.isNotEmpty() }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** [PodioOpml.encode] with each tagged show inside a folder of its first tag (A to Z), the others at the top. */
    fun opml(saved: PodioSaved): String {
        val plain = PodioOpml.encode(saved.shows)
        val tagged = saved.shows.groupBy { show -> saved.showTags[show.id].orEmpty().minByOrNull { it.lowercase() } }
        if (tagged.keys.all { it == null }) return plain
        val lines = plain.lines()
        val head = lines.takeWhile { !it.startsWith("  <outline") }
        val outlineOf = saved.shows.associate { show -> show.id to (lines.firstOrNull { it.contains("=\"" + escape(show.feed) + "\"") } ?: "") }
        return buildString {
            head.forEach { append(it).append('\n') }
            tagged[null].orEmpty().forEach { outlineOf[it.id]?.takeIf(String::isNotEmpty)?.let { line -> append(line).append('\n') } }
            tagged.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { tag ->
                append("  <outline text=\"").append(escape(tag)).append("\" title=\"").append(escape(tag)).append("\">\n")
                tagged.getValue(tag).forEach { outlineOf[it.id]?.takeIf(String::isNotEmpty)?.let { line -> append("  ").append(line).append('\n') } }
                append("  </outline>\n")
            }
            append("</body></opml>\n")
        }
    }

    private fun escape(value: String): String = value.filter { it >= ' ' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

/** Episodes the listener keeps as favourites (AntennaPod's favourites), newest first. */
object PodioFavourites {
    const val MAX = 500

    fun isFavourite(saved: PodioSaved, episodeId: String): Boolean = saved.favouriteEpisodes.any { it.id == episodeId }

    /** [entry] added at the front, or taken out when it is a favourite already. */
    fun toggle(saved: PodioSaved, entry: PodioQueueEntry): PodioSaved =
        if (isFavourite(saved, entry.id)) saved.copy(favouriteEpisodes = saved.favouriteEpisodes.filterNot { it.id == entry.id })
        else saved.copy(favouriteEpisodes = (listOf(entry.copy(playNext = false)) + saved.favouriteEpisodes).take(MAX))
}
