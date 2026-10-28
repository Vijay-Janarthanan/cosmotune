package com.cosmotune.podio

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Portable subscriptions: no listening positions, device files or radio stations in an OPML export. */
object PodioOpml {
    fun read(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 1024 * 1024) { "The OPML document exceeds 1 MiB." }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
    fun decode(xml: String): List<PodcastShow> {
        require(xml.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "The OPML document exceeds 1 MiB." }
        require(!xml.contains("<!DOCTYPE", true)) { "External XML declarations are not supported." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))))
        require(document.documentElement.tagName.equals("opml", true)) { "This is not an OPML document." }
        val outlines = document.getElementsByTagName("outline")
        val shows = (0 until outlines.length).mapNotNull { index ->
            val outline = outlines.item(index) as? Element ?: return@mapNotNull null
            val raw = outline.getAttribute("xmlUrl").ifBlank { outline.getAttribute("xmlurl") }.ifBlank {
                outline.getAttribute("url").takeIf { YouTubePodcasts.showId(it) != null }.orEmpty()
            }
            val url = PodioCatalogue.httpUrl(raw)
                ?: return@mapNotNull null
            val title = outline.getAttribute("title").ifBlank { outline.getAttribute("text") }.ifBlank { java.net.URI(url).host }.take(300)
            PodcastShow(YouTubePodcasts.showId(url)?.let { "youtube:$it" } ?: PodioCatalogue.identity(url), title, "", url,
                website = PodioCatalogue.httpUrl(outline.getAttribute("htmlUrl")).orEmpty(), source = "OPML subscription")
        }.distinctBy { it.feed.trimEnd('/') }
        require(shows.size <= 500) { "Import up to 500 podcast subscriptions at a time." }
        require(shows.isNotEmpty()) { "This OPML document contains no HTTP or HTTPS podcast feeds." }
        return shows
    }

    fun encode(shows: List<PodcastShow>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\"><head><title>Cosmotune podcasts</title></head><body>\n")
        shows.filter { PodioCatalogue.httpUrl(it.feed) != null }.distinctBy { it.feed.trimEnd('/') }.take(500).forEach { show ->
            val youtube = YouTubePodcasts.showId(show.feed) != null
            append("  <outline type=\"").append(if (youtube) "link" else "rss").append("\" text=\"").append(escape(show.title)).append("\" title=\"").append(escape(show.title))
            append(if (youtube) "\" url=\"" else "\" xmlUrl=\"").append(escape(show.feed)).append("\"")
            PodioCatalogue.httpUrl(show.website)?.let { append(" htmlUrl=\"").append(escape(it)).append("\"") }
            append("/>\n")
        }
        append("</body></opml>\n")
    }

    private fun escape(value: String): String = value.filter { it >= ' ' || it in "\t\n\r" }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
