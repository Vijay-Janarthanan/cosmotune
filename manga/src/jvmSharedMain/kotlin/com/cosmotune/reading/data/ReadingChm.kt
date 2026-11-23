package com.cosmotune.reading.data

import org.apache.tika.parser.microsoft.chm.ChmExtractor
import org.apache.tika.parser.microsoft.chm.DirectoryListingEntry
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder

/**
 * Compiled help books (CHM), unpacked with Apache Tika's CHM reader: the pages in the order of the book's contents (its .hhc
 * file), each with its pictures, as chapters. Pages not in the contents follow in name order when there are no contents.
 */
internal object ReadingChm {
    private const val MAX_PAGES = 2_000

    fun convert(file: File, fallbackTitle: String): ReadingConvert.Book {
        val extractor = try { file.inputStream().buffered().use { ChmExtractor(it) } }
            catch (e: Exception) { throw IllegalArgumentException("This CHM file is damaged or not a help book.", e) }
        val entries = extractor.chmDirList.directoryListingEntryList.associateBy { key(it.name) }
        val cache = HashMap<String, ByteArray?>()
        fun bytes(name: String): ByteArray? = cache.getOrPut(key(name)) {
            entries[key(name)]?.takeIf { it.length > 0 }?.let { entry: DirectoryListingEntry -> runCatching { extractor.extractChmEntry(entry) }.getOrNull() }
        }
        val contents = entries.keys.firstOrNull { it.endsWith(".hhc") }?.let { bytes(it) }?.let { toc(ReadingConvert.decode(it)) }.orEmpty()
        val pages = contents.ifEmpty {
            entries.keys.filter { (it.endsWith(".htm") || it.endsWith(".html")) && !it.startsWith("/#") && !it.startsWith("/$") }
                .sortedWith { a, b -> ReadingConvert.natural(a, b) }.map { "" to it }
        }.distinctBy { it.second }.take(MAX_PAGES)
        val images = ReadingConvert.ImageSink()
        val chapters = ArrayList<ReadingConvert.Chapter>()
        var title: String? = null
        pages.forEach { (name, path) ->
            val raw = bytes(path) ?: return@forEach
            val document = Jsoup.parse(ReadingConvert.decode(raw))
            val folder = path.substringBeforeLast('/', "")
            val page = ReadingConvert.html(document, name.ifBlank { fallbackTitle }, images) { source -> bytes(resolve(folder, source)) }
            if (title == null) title = document.title().takeIf { it.isNotBlank() }
            val parts = page.chapters.filter { it.html.isNotBlank() }
            // A page is one topic: its name from the contents, unless it has its own headings to split at.
            if (parts.size == 1 && name.isNotBlank()) chapters += ReadingConvert.Chapter(name, parts[0].html) else chapters += parts
        }
        require(chapters.isNotEmpty()) { "This help book has no pages to read." }
        return ReadingConvert.Book(title?.takeIf { contents.isNotEmpty() || pages.size == 1 } ?: fallbackTitle, chapters = chapters, images = images.images)
    }

    /** The contents' topics in order, as (name, page path); fragments and other books' pages are left out. */
    internal fun toc(hhc: String): List<Pair<String, String>> = Jsoup.parse(hhc).select("object[type=text/sitemap]").mapNotNull { item ->
        val params = item.select("param").associate { it.attr("name").lowercase() to it.attr("value") }
        val local = params["local"]?.substringAfter("::")?.substringBefore('#')?.trim()?.takeIf { it.isNotEmpty() && !it.contains("://") } ?: return@mapNotNull null
        params["name"].orEmpty().trim() to key(local)
    }

    /** [source] from a page in [folder], as an entry's key ("/images/a.gif"). */
    internal fun resolve(folder: String, source: String): String {
        val clean = source.substringAfter("::").substringBefore('#').substringBefore('?')
        if (clean.startsWith("/")) return key(clean)
        val parts = ArrayList(folder.split('/').filter { it.isNotEmpty() })
        clean.split('/').forEach { part -> when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts += part } }
        return key(parts.joinToString("/"))
    }

    private fun key(name: String): String {
        val decoded = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name).replace('\\', '/')
        return "/" + decoded.trimStart('/').lowercase()
    }
}
