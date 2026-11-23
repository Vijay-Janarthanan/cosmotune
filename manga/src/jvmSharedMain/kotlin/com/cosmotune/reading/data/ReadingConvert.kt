package com.cosmotune.reading.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

/**
 * Turns the book files people already own (MOBI and Kindle AZW3, FB2, TXT, HTML, Markdown, Word DOCX, RTF, saved web pages and
 * CBZ comics) into a small EPUB package on import, so the reader, search, bookmarks and highlights work the same for every format.
 * Nothing is fetched: images come from inside the file only. Protected (DRM) Kindle books are refused with a plain message.
 */
internal object ReadingConvert {
    /** The file kinds Read converts, by extension. */
    val extensions = setOf("txt", "text", "md", "markdown", "html", "htm", "xhtml", "mhtml", "mht", "fb2", "fbz", "docx", "rtf",
        "mobi", "azw", "azw3", "prc", "cbz")

    /** The converted kind of [name], or null when Read does not convert it ("book.fb2.zip" is FB2). */
    fun kindOf(name: String): String? {
        val lower = name.lowercase()
        if (lower.endsWith(".fb2.zip")) return "fbz"
        return lower.substringAfterLast('.', "").takeIf { it in extensions }
    }

    /** How the book's Details names its original file. */
    fun label(kind: String): String = when (kind) {
        "txt", "text" -> "TXT"; "md", "markdown" -> "Markdown"; "html", "htm", "xhtml" -> "HTML"; "mhtml", "mht" -> "Web page"
        "fb2", "fbz" -> "FB2"; "docx" -> "Word"; "rtf" -> "RTF"; "mobi", "prc" -> "MOBI"; "azw" -> "AZW"; "azw3" -> "AZW3"; "cbz" -> "CBZ"
        else -> kind.uppercase()
    }

    class Chapter(val title: String, val html: String)
    class Image(val name: String, val mediaType: String, val bytes: ByteArray)
    class Book(val title: String, val authors: List<String> = emptyList(), val language: String = "", val description: String = "",
        val chapters: List<Chapter>, val images: List<Image> = emptyList(), val cover: String? = null, val css: List<String> = emptyList(),
        /** Picture books (CBZ) keep one image to a page with no margins. */
        val pictures: Boolean = false)

    fun convert(kind: String, file: File, fallbackTitle: String): Book {
        require(file.length() <= MAX_INPUT) { "This book is too large to convert (the limit is 128 MB)." }
        val book = when (kind) {
            "txt", "text" -> text(decode(file.readBytes()), fallbackTitle)
            "md", "markdown" -> markdown(decode(file.readBytes()), fallbackTitle)
            "html", "htm", "xhtml" -> html(Jsoup.parse(decode(file.readBytes())), fallbackTitle, ImageSink())
            "mhtml", "mht" -> mhtml(file.readBytes(), fallbackTitle)
            "fb2" -> ReadingConvertDocs.fb2(file.readBytes(), fallbackTitle)
            "fbz" -> ReadingConvertDocs.fb2(zippedFb2(file), fallbackTitle)
            "docx" -> ReadingConvertDocs.docx(file, fallbackTitle)
            "rtf" -> ReadingConvertDocs.rtf(file.readBytes(), fallbackTitle)
            "mobi", "azw", "azw3", "prc" -> ReadingMobi.convert(file.readBytes(), fallbackTitle)
            "cbz" -> cbz(file, fallbackTitle)
            else -> throw IllegalArgumentException("Read cannot open this kind of file.")
        }
        require(book.chapters.isNotEmpty() && book.chapters.any { it.html.isNotBlank() }) { "This file has no text to read." }
        return book
    }

    // ---- Text ----------------------------------------------------------------------------------------------------------------

    /** Text in its own encoding: a byte-order mark first, then strict UTF-8, then Windows-1252 (old Western text files). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { String(bytes, Charset.forName("windows-1252")) }
    }

    private val chapterLine = Regex("""(?i)^\s*(chapter|part|book|prologue|epilogue|preface|introduction|section|act)\b[\s\S]{0,70}$|^\s*[IVXLC]{1,7}\.?\s*$|^\s*\d{1,3}\.?\s*$""")

    /** Plain text: paragraphs split on blank lines (or on every line when there are none), chapters on "Chapter 3"-style lines. */
    fun text(source: String, fallbackTitle: String): Book {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blankSeparated = lines.count { it.isBlank() } >= lines.size / 20 && lines.any { it.isBlank() }
        // Paragraphs, each with whether it stands alone (a likely heading).
        val paragraphs = ArrayList<Pair<String, Boolean>>()
        if (blankSeparated) {
            val current = ArrayList<String>()
            fun flush() { if (current.isNotEmpty()) { paragraphs += current.joinToString(" ") { it.trim() } to (current.size == 1); current.clear() } }
            lines.forEach { line -> if (line.isBlank()) flush() else current += line }
            flush()
        } else lines.filter { it.isNotBlank() }.forEach { paragraphs += it.trim() to true }
        val headings = paragraphs.indices.filter { paragraphs[it].second && paragraphs[it].first.length <= 80 && chapterLine.matches(paragraphs[it].first) }
        val chapters = ArrayList<Chapter>()
        fun body(from: Int, to: Int, heading: String?) = buildString {
            if (heading != null) append("<h2>").append(escape(heading)).append("</h2>")
            for (i in from until to) append("<p>").append(escape(paragraphs[i].first)).append("</p>")
        }
        if (headings.size >= 2) {
            if (headings.first() > 0) chapters += Chapter("Opening", body(0, headings.first(), null))
            headings.forEachIndexed { index, start ->
                val end = headings.getOrNull(index + 1) ?: paragraphs.size
                chapters += Chapter(paragraphs[start].first, body(start + 1, end, paragraphs[start].first))
            }
        } else {
            paragraphs.indices.chunked(PARAGRAPHS_PER_PART).forEachIndexed { index, part ->
                chapters += Chapter(if (paragraphs.size <= PARAGRAPHS_PER_PART) fallbackTitle else "Part ${index + 1}", body(part.first(), part.last() + 1, null))
            }
        }
        return Book(fallbackTitle, chapters = chapters)
    }

    // ---- Markdown ------------------------------------------------------------------------------------------------------------

    /** Markdown's common parts: headings, paragraphs, emphasis, code, quotes, lists and rules; links keep their words. */
    fun markdown(source: String, fallbackTitle: String): Book {
        val html = StringBuilder()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        val paragraph = ArrayList<String>()
        fun flush() { if (paragraph.isNotEmpty()) { html.append("<p>").append(inline(paragraph.joinToString(" "))).append("</p>\n"); paragraph.clear() } }
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val code = ArrayList<String>()
                    index++
                    while (index < lines.size && !lines[index].trim().startsWith(fence)) { code += lines[index]; index++ }
                    html.append("<pre><code>").append(escape(code.joinToString("\n"))).append("</code></pre>\n")
                }
                trimmed.isEmpty() -> flush()
                Regex("^#{1,6}\\s").containsMatchIn(trimmed) -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    html.append("<h$level>").append(inline(trimmed.drop(level).trim().trimEnd('#').trim())).append("</h$level>\n")
                }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed.replace(" ", "")) -> { flush(); html.append("<hr/>\n") }
                index + 1 < lines.size && paragraph.isEmpty() && Regex("^=+$").matches(lines[index + 1].trim()) && trimmed.isNotEmpty() -> {
                    html.append("<h1>").append(inline(trimmed)).append("</h1>\n"); index++
                }
                index + 1 < lines.size && paragraph.isEmpty() && Regex("^-+$").matches(lines[index + 1].trim()) && trimmed.isNotEmpty() -> {
                    html.append("<h2>").append(inline(trimmed)).append("</h2>\n"); index++
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = ArrayList<String>()
                    while (index < lines.size && lines[index].trim().startsWith(">")) { quote += lines[index].trim().removePrefix(">").trim(); index++ }
                    index--
                    html.append("<blockquote><p>").append(inline(quote.joinToString(" "))).append("</p></blockquote>\n")
                }
                Regex("^([-*+]|\\d{1,9}[.)])\\s+").containsMatchIn(trimmed) -> {
                    flush()
                    val ordered = trimmed.first().isDigit()
                    html.append(if (ordered) "<ol>" else "<ul>")
                    while (index < lines.size && Regex("^([-*+]|\\d{1,9}[.)])\\s+").containsMatchIn(lines[index].trim())) {
                        html.append("<li>").append(inline(lines[index].trim().replaceFirst(Regex("^([-*+]|\\d{1,9}[.)])\\s+"), ""))).append("</li>")
                        index++
                    }
                    index--
                    html.append(if (ordered) "</ol>\n" else "</ul>\n")
                }
                else -> paragraph += trimmed
            }
            index++
        }
        flush()
        val document = Jsoup.parseBodyFragment(html.toString())
        val title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        return Book(title, chapters = splitAtHeadings(document.body(), title))
    }

    private fun inline(text: String): String {
        var value = escape(text)
        value = value.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        value = value.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)")) { it.groupValues[1] }
        value = value.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)")) { it.groupValues[1] }
        value = value.replace(Regex("(\\*\\*|__)(.+?)\\1")) { "<strong>${it.groupValues[2]}</strong>" }
        value = value.replace(Regex("(?<![\\w*])([*_])(?!\\s)(.+?)(?<!\\s)\\1(?![\\w*])")) { "<em>${it.groupValues[2]}</em>" }
        value = value.replace(Regex("~~(.+?)~~")) { "<s>${it.groupValues[1]}</s>" }
        return value
    }

    // ---- HTML and saved web pages --------------------------------------------------------------------------------------------

    /** Images kept from inside a file, named in the order they are found. */
    class ImageSink {
        val images = ArrayList<Image>()
        private val byKey = HashMap<String, String>()
        /** The package name of [bytes] (a recognised picture), or null to drop the reference. */
        fun add(key: String, bytes: ByteArray): String? {
            byKey[key]?.let { return it }
            val type = imageType(bytes) ?: return null
            if (images.size >= MAX_IMAGES || bytes.size > MAX_IMAGE) return null
            val name = "img%04d.%s".format(images.size + 1, extensionOf(type))
            images += Image(name, type, bytes)
            byKey[key] = name
            return name
        }
    }

    /**
     * An HTML document: its title and author from the head, pictures embedded as data addresses (or handed in by [resolve], for a
     * saved web page), chapters at the top headings.
     */
    fun html(document: Document, fallbackTitle: String, images: ImageSink, resolve: (String) -> ByteArray? = { null }): Book {
        val title = document.title().takeIf { it.isNotBlank() } ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val author = document.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }
        document.select("script, style, noscript, iframe, object, embed, form, nav[aria-hidden], template").remove()
        document.select("img").forEach { image ->
            val source = image.attr("src").trim()
            val bytes = when {
                source.startsWith("data:", true) && ";base64," in source ->
                    runCatching { Base64.getMimeDecoder().decode(source.substringAfter(";base64,")) }.getOrNull()
                source.isNotEmpty() -> resolve(source)
                else -> null
            }
            val name = bytes?.let { images.add(source.take(256), it) }
            if (name == null) image.remove() else { image.attributes().asList().map { it.key }.forEach(image::removeAttr); image.attr("src", "images/$name") }
        }
        // Links to other places have nowhere to go inside the book; their words stay.
        document.select("a[href]").forEach { link -> if (!link.attr("href").startsWith("#")) link.unwrap() }
        return Book(title, listOfNotNull(author), document.selectFirst("html")?.attr("lang").orEmpty(), chapters = splitAtHeadings(document.body(), title),
            images = images.images)
    }

    /** A saved web page (MHTML): the page and the pictures saved with it. */
    fun mhtml(bytes: ByteArray, fallbackTitle: String): Book {
        val text = String(bytes, Charsets.ISO_8859_1)
        val headerEnd = Regex("\r?\n\r?\n").find(text) ?: throw IllegalArgumentException("This saved web page is damaged.")
        val headers = mimeHeaders(text.substring(0, headerEnd.range.first))
        val boundary = Regex("(?i)boundary=\"?([^\";\r\n]+)\"?").find(headers["content-type"].orEmpty())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("This saved web page is damaged.")
        val parts = text.substring(headerEnd.range.last + 1).split("--$boundary").mapNotNull { raw ->
            val part = raw.trimStart('\r', '\n')
            if (part.startsWith("--")) return@mapNotNull null
            val end = Regex("\r?\n\r?\n").find(part) ?: return@mapNotNull null
            val partHeaders = mimeHeaders(part.substring(0, end.range.first))
            val body = part.substring(end.range.last + 1).trimEnd('\r', '\n', '-')
            val data = when (partHeaders["content-transfer-encoding"]?.trim()?.lowercase()) {
                "base64" -> runCatching { Base64.getMimeDecoder().decode(body.trim()) }.getOrNull() ?: return@mapNotNull null
                "quoted-printable" -> quotedPrintable(body)
                else -> body.toByteArray(Charsets.ISO_8859_1)
            }
            partHeaders to data
        }
        val page = parts.firstOrNull { it.first["content-type"].orEmpty().lowercase().startsWith("text/html") }
            ?: throw IllegalArgumentException("This saved web page has no page in it.")
        val charset = Regex("(?i)charset=\"?([\\w-]+)").find(page.first["content-type"].orEmpty())?.groupValues?.get(1)
        val html = charset?.let { runCatching { String(page.second, Charset.forName(it)) }.getOrNull() } ?: decode(page.second)
        val byLocation = HashMap<String, ByteArray>()
        parts.forEach { (partHeaders, data) ->
            partHeaders["content-location"]?.trim()?.let { byLocation[it] = data }
            partHeaders["content-id"]?.trim()?.trim('<', '>')?.let { byLocation["cid:$it"] = data }
        }
        val base = page.first["content-location"]?.trim()
        return html(Jsoup.parse(html), fallbackTitle, ImageSink()) { source ->
            byLocation[source] ?: base?.let { runCatching { java.net.URI(it).resolve(source).toString() }.getOrNull() }?.let(byLocation::get)
        }
    }

    private fun mimeHeaders(block: String): Map<String, String> {
        val result = HashMap<String, String>()
        var last: String? = null
        block.split(Regex("\r?\n")).forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && last != null) result[last!!] = result[last!!] + " " + line.trim()
            else if (':' in line) { last = line.substringBefore(':').trim().lowercase(); result[last!!] = line.substringAfter(':').trim() }
        }
        return result
    }

    private fun quotedPrintable(text: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val clean = text.replace(Regex("=\r?\n"), "")
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            if (c == '=' && i + 2 < clean.length) {
                val value = clean.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) { output.write(value); i += 3; continue }
            }
            output.write(c.code and 0xFF); i++
        }
        return output.toByteArray()
    }

    // ---- CBZ ------------------------------------------------------------------------------------------------------------------

    /** A comic archive as a picture book: every picture a page, in the archive's natural order. */
    fun cbz(file: File, fallbackTitle: String): Book {
        val images = ImageSink()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX") && it.size <= MAX_IMAGE }
                .sortedWith { a, b -> natural(a.name, b.name) }.toList()
            require(entries.size <= 10_000) { "This comic has too many pages." }
            entries.forEach { entry -> images.add(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }
        }
        require(images.images.isNotEmpty()) { "This comic archive has no pictures in it." }
        val chapters = images.images.mapIndexed { index, image -> Chapter("Page ${index + 1}", "<div class=\"page\"><img src=\"images/${image.name}\" alt=\"Page ${index + 1}\"/></div>") }
        return Book(fallbackTitle, chapters = chapters, images = images.images, cover = images.images.first().name, pictures = true)
    }

    internal fun natural(a: String, b: String): Int {
        val split = Regex("(\\d+)")
        val left = split.split(a.lowercase()).zip(split.findAll(a.lowercase()).map { it.value }.toList() + "") { text, number -> text to number }
        val right = split.split(b.lowercase()).zip(split.findAll(b.lowercase()).map { it.value }.toList() + "") { text, number -> text to number }
        for (i in 0 until minOf(left.size, right.size)) {
            val text = left[i].first.compareTo(right[i].first)
            if (text != 0) return text
            val l = left[i].second.toBigIntegerOrNull(); val r = right[i].second.toBigIntegerOrNull()
            val number = if (l != null && r != null) l.compareTo(r) else left[i].second.compareTo(right[i].second)
            if (number != 0) return number
        }
        return left.size - right.size
    }

    private fun zippedFb2(file: File): ByteArray = ZipFile(file).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
            ?: throw IllegalArgumentException("This archive has no FB2 book in it.")
        require(entry.size <= MAX_INPUT) { "This book is too large to convert." }
        zip.getInputStream(entry).use { it.readBytes() }
    }

    // ---- Chapters -------------------------------------------------------------------------------------------------------------

    /** [body] cut into chapters at its top headings (h1, else h2), looking through wrappers that hold the whole text. */
    fun splitAtHeadings(body: Element, title: String): List<Chapter> {
        var container = body
        while (container.children().size == 1 && container.child(0).normalName() in setOf("div", "section", "article", "main") &&
            container.textNodes().all { it.isBlank }) container = container.child(0)
        val nodes = container.childNodes().toList()
        val level = listOf("h1", "h2").firstOrNull { tag -> container.children().count { it.normalName() == tag } >= 2 }
        if (level == null) return listOf(Chapter(title, container.html()))
        val chapters = ArrayList<Chapter>()
        var current = StringBuilder()
        var currentTitle: String? = null
        fun flush() {
            val html = current.toString()
            if (html.isNotBlank() && Jsoup.parseBodyFragment(html).body().let { it.text().isNotBlank() || it.select("img").isNotEmpty() })
                chapters += Chapter(currentTitle ?: if (chapters.isEmpty()) title else "Part ${chapters.size + 1}", html)
            current = StringBuilder()
        }
        nodes.forEach { node ->
            if (node is Element && node.normalName() == level) { flush(); currentTitle = node.text().take(200) }
            current.append(node.outerHtml())
        }
        flush()
        return chapters.ifEmpty { listOf(Chapter(title, container.html())) }
    }

    fun escape(value: String): String = buildString(value.length) {
        value.forEach { c -> when (c) { '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); else -> if (c.code >= 32 || c == '\n' || c == '\t') append(c) } }
    }

    fun imageType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 5 && String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1).trimStart().let { it.startsWith("<svg") || (it.startsWith("<?xml") && "<svg" in it) } -> "image/svg+xml"
        else -> null
    }
    private fun extensionOf(type: String) = when (type) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "svg" }

    // ---- The EPUB package -----------------------------------------------------------------------------------------------------

    /** Writes [book] as an EPUB 3 package at [target]; the same book always gives the same bytes. */
    fun writeEpub(book: Book, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                if (stored) {
                    entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII), stored = true)
            put("META-INF/container.xml", """<?xml version="1.0" encoding="utf-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            val titles = book.chapters.mapIndexed { index, chapter -> chapter.title.trim().take(200).ifBlank { "Chapter ${index + 1}" } }
            val names = book.chapters.indices.map { "c%04d.xhtml".format(it + 1) }
            val css = buildString {
                append(if (book.pictures) "body{margin:0;padding:0;text-align:center}div.page{margin:0}div.page img{max-width:100%;height:auto}"
                    else "body{line-height:1.5}p{margin:0 0 .8em}h1,h2,h3{line-height:1.25}blockquote{margin:1em 1.5em;font-style:italic}pre{white-space:pre-wrap}img{max-width:100%;height:auto}.poem{margin:1em 2em}.author{text-align:right;font-style:italic}table{border-collapse:collapse}td,th{border:1px solid #8888;padding:.2em .4em}")
                book.css.forEach { append('\n').append(it) }
            }
            put("OEBPS/style.css", css.toByteArray())
            book.chapters.forEachIndexed { index, chapter ->
                put("OEBPS/${names[index]}", xhtml(titles[index], chapter.html, book.language).toByteArray())
            }
            put("OEBPS/nav.xhtml", buildString {
                append("""<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>""")
                titles.forEachIndexed { index, title -> append("<li><a href=\"").append(names[index]).append("\">").append(escape(title)).append("</a></li>") }
                append("</ol></nav></body></html>")
            }.toByteArray())
            book.images.forEach { image -> put("OEBPS/images/${image.name}", image.bytes) }
            put("OEBPS/content.opf", buildString {
                append("""<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                append("<dc:identifier id=\"id\">cosmotune-converted</dc:identifier>")
                append("<dc:title>").append(escape(book.title.take(300))).append("</dc:title>")
                book.authors.filter { it.isNotBlank() }.take(20).forEach { append("<dc:creator>").append(escape(it.take(200))).append("</dc:creator>") }
                append("<dc:language>").append(escape(book.language.ifBlank { "und" }.take(35))).append("</dc:language>")
                if (book.description.isNotBlank()) append("<dc:description>").append(escape(book.description.take(20_000))).append("</dc:description>")
                book.cover?.let { append("<meta name=\"cover\" content=\"cover\"/>") }
                append("</metadata><manifest>")
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
                names.forEachIndexed { index, name -> append("<item id=\"c${index + 1}\" href=\"$name\" media-type=\"application/xhtml+xml\"/>") }
                book.images.forEachIndexed { index, image ->
                    val id = if (image.name == book.cover) "cover" else "i${index + 1}"
                    append("<item id=\"$id\" href=\"images/${image.name}\" media-type=\"${image.mediaType}\"")
                    if (image.name == book.cover) append(" properties=\"cover-image\"")
                    append("/>")
                }
                append("</manifest><spine>")
                names.indices.forEach { append("<itemref idref=\"c${it + 1}\"/>") }
                append("</spine></package>")
            }.toByteArray())
        }
    }

    private fun xhtml(title: String, body: String, language: String): String {
        val document = Jsoup.parseBodyFragment(body)
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).charset(Charsets.UTF_8).prettyPrint(false)
        // Stray control characters are not allowed in XHTML.
        document.body().allElements.forEach { element -> element.textNodes().forEach { node: TextNode -> node.text(node.wholeText.filter { it.code >= 32 || it == '\n' || it == '\t' }) } }
        val lang = language.takeIf { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9-]{1,35}")) }?.let { " lang=\"$it\" xml:lang=\"$it\"" }.orEmpty()
        return """<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"$lang><head><title>${escape(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head><body>""" +
            document.body().html() + "</body></html>"
    }

    private const val MAX_INPUT = 128L * 1024 * 1024
    private const val MAX_IMAGE = 32 * 1024 * 1024
    private const val MAX_IMAGES = 5_000
    private const val PARAGRAPHS_PER_PART = 250
    private const val FIXED_TIME = 315_576_000_000L // 1980-01-01, the first time a ZIP can hold.
}
