package com.cosmotune.reading.data

import com.cosmotune.reading.data.ReadingConvert.Book
import com.cosmotune.reading.data.ReadingConvert.Chapter
import com.cosmotune.reading.data.ReadingConvert.ImageSink
import com.cosmotune.reading.data.ReadingConvert.escape
import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** FictionBook (FB2), Word (DOCX) and RTF to chapters of simple HTML for [ReadingConvert]. */
internal object ReadingConvertDocs {
    private fun Element.local() = tagName().substringAfter(':').lowercase()
    private fun Element.kids(name: String) = children().filter { it.local() == name }
    private fun Element.kid(name: String) = children().firstOrNull { it.local() == name }
    private fun xml(text: String): Element {
        // Entity declarations are refused, as for EPUB; Jsoup's XML parser never loads outside documents.
        require(!text.contains("<!ENTITY", true)) { "This file declares entities, which Read does not open." }
        return Jsoup.parse(text, "", Parser.xmlParser())
    }

    // ---- FB2 ------------------------------------------------------------------------------------------------------------------

    fun fb2(bytes: ByteArray, fallbackTitle: String): Book {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding=[\"']([\\w-]+)[\"']").find(head)?.groupValues?.get(1)
        val text = declared?.let { runCatching { String(bytes, Charset.forName(it)) }.getOrNull() } ?: ReadingConvert.decode(bytes)
        val root = xml(text).allElements.firstOrNull { it.local() == "fictionbook" } ?: throw IllegalArgumentException("This is not an FB2 book.")
        val info = root.kid("description")?.kid("title-info")
        val title = info?.kid("book-title")?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val authors = info?.kids("author").orEmpty().map { author ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { author.kid(it)?.text()?.takeIf(String::isNotBlank) }.joinToString(" ")
                .ifBlank { author.kid("nickname")?.text().orEmpty() }
        }.filter { it.isNotBlank() }
        val images = ImageSink()
        val binaries = HashMap<String, String>()
        root.kids("binary").forEach { binary ->
            val bytesOf = runCatching { Base64.getMimeDecoder().decode(binary.wholeText().trim()) }.getOrNull() ?: return@forEach
            images.add(binary.attr("id"), bytesOf)?.let { binaries[binary.attr("id")] = it }
        }
        fun href(element: Element) = element.attributes().asList().firstOrNull { it.key.substringAfter(':') == "href" }?.value.orEmpty()
        val cover = info?.kid("coverpage")?.kid("image")?.let { binaries[href(it).removePrefix("#")] }
        val bodies = root.kids("body")
        val notes = bodies.filter { it.attr("name").lowercase() in setOf("notes", "comments") }
        val main = bodies.firstOrNull { it !in notes } ?: throw IllegalArgumentException("This FB2 book has no text.")
        fun convert(node: Node, out: StringBuilder, depth: Int) {
            if (node is TextNode) { out.append(escape(node.wholeText)); return }
            if (node !is Element) return
            fun inner() = node.childNodes().forEach { convert(it, out, depth) }
            fun wrap(tag: String, attributes: String = "") { out.append("<$tag$attributes>"); inner(); out.append("</$tag>") }
            when (node.local()) {
                "section" -> { out.append("<div class=\"section\">"); node.childNodes().forEach { convert(it, out, depth + 1) }; out.append("</div>") }
                "title" -> { val level = (depth + 1).coerceIn(2, 4); out.append("<h$level>"); out.append(node.kids("p").joinToString(" · ") { escape(it.text()) }.ifBlank { escape(node.text()) }); out.append("</h$level>") }
                "subtitle" -> wrap("h4")
                "p", "v" -> wrap("p")
                "emphasis" -> wrap("em")
                "strong" -> wrap("strong")
                "strikethrough" -> wrap("s")
                "sub", "sup", "code" -> wrap(node.local())
                "empty-line" -> out.append("<p>&#160;</p>")
                "poem", "stanza" -> wrap("div", " class=\"poem\"")
                "epigraph", "cite" -> wrap("blockquote")
                "text-author" -> wrap("p", " class=\"author\"")
                "table" -> wrap("table"); "tr" -> wrap("tr"); "td" -> wrap("td"); "th" -> wrap("th")
                "image" -> binaries[href(node).removePrefix("#")]?.let { out.append("<img src=\"images/$it\" alt=\"\"/>") }
                "a" -> {
                    val target = href(node)
                    // A note reference opens the Notes chapter at that note.
                    if (target.startsWith("#") && notes.isNotEmpty()) wrap("a", " href=\"NOTES_CHAPTER#${escape(target.drop(1))}\" epub:type=\"noteref\"") else inner()
                }
                else -> inner()
            }
        }
        val chapters = ArrayList<Chapter>()
        val sections = main.kids("section")
        val intro = StringBuilder()
        main.kid("title")?.let { convert(it, intro, 0) }
        main.kids("epigraph").forEach { convert(it, intro, 0) }
        if (sections.isEmpty()) { main.childNodes().forEach { convert(it, intro, 0) }; chapters += Chapter(title, intro.toString()) }
        else {
            if (intro.isNotBlank()) chapters += Chapter(title, intro.toString())
            sections.forEachIndexed { index, section ->
                val out = StringBuilder()
                convert(section, out, 0)
                chapters += Chapter(section.kid("title")?.text()?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}", out.toString())
            }
        }
        if (notes.isNotEmpty()) {
            val out = StringBuilder("<h2>Notes</h2>")
            notes.forEach { body -> body.allElements.filter { it.local() == "section" && it.hasAttr("id") }.forEach { note ->
                out.append("<aside id=\"").append(escape(note.attr("id"))).append("\" epub:type=\"footnote\">")
                note.childNodes().forEach { convert(it, out, 3) }
                out.append("</aside>")
            } }
            chapters += Chapter("Notes", out.toString())
        }
        val notesName = "c%04d.xhtml".format(chapters.size)
        return Book(title, authors, info?.kid("lang")?.text().orEmpty(), info?.kid("annotation")?.text().orEmpty(),
            chapters.map { Chapter(it.title, it.html.replace("NOTES_CHAPTER", notesName)) }, images.images, cover)
    }

    // ---- DOCX -----------------------------------------------------------------------------------------------------------------

    fun docx(file: File, fallbackTitle: String): Book = ZipFile(file).use { zip ->
        fun read(name: String): String? = zip.getEntry(name)?.takeIf { it.size <= 64L * 1024 * 1024 }?.let { entry -> zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) } }
        val document = read("word/document.xml")?.let(::xml) ?: throw IllegalArgumentException("This is not a Word document.")
        val relations = read("word/_rels/document.xml.rels")?.let(::xml)?.allElements.orEmpty().filter { it.local() == "relationship" }
            .associate { it.attr("Id") to it.attr("Target") }
        val styles = read("word/styles.xml")?.let(::xml)?.allElements.orEmpty().filter { it.local() == "style" }
            .associate { style -> style.attr("w:styleId") to (style.kid("name")?.attr("w:val")?.lowercase().orEmpty()) }
        val core = read("docProps/core.xml")?.let(::xml)
        val title = core?.allElements?.firstOrNull { it.local() == "title" }?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val author = core?.allElements?.firstOrNull { it.local() == "creator" }?.text()?.takeIf { it.isNotBlank() }
        val images = ImageSink()
        fun image(id: String): String? {
            val target = relations[id] ?: return null
            if (target.startsWith("http", true)) return null
            val path = java.net.URI("word/").resolve(target.removePrefix("/")).normalize().path.removePrefix("/")
            val entry = zip.getEntry(path) ?: zip.getEntry(target.removePrefix("/")) ?: return null
            if (entry.size > 32L * 1024 * 1024) return null
            return images.add(path, zip.getInputStream(entry).use { it.readBytes() })
        }
        fun on(element: Element?): Boolean = element != null && element.attr("w:val").lowercase() !in setOf("0", "false", "none")
        fun runs(paragraph: Element, out: StringBuilder) {
            paragraph.allElements.filter { it.local() == "r" }.forEach { run ->
                val properties = run.kid("rpr")
                val bold = on(properties?.kid("b")); val italic = on(properties?.kid("i")); val underline = on(properties?.kid("u"))
                val text = StringBuilder()
                run.children().forEach { part ->
                    when (part.local()) {
                        "t" -> text.append(escape(part.wholeText()))
                        "tab" -> text.append(' ')
                        "br", "cr" -> text.append("<br/>")
                        "drawing", "pict" -> part.allElements.firstOrNull { it.local() in setOf("blip", "imagedata") }?.let { blip ->
                            val id = blip.attributes().asList().firstOrNull { it.key.endsWith(":embed") || it.key.endsWith(":id") }?.value
                            id?.let(::image)?.let { text.append("<img src=\"images/$it\" alt=\"\"/>") }
                        }
                    }
                }
                if (text.isEmpty()) return@forEach
                var value = text.toString()
                if (underline) value = "<u>$value</u>"
                if (italic) value = "<em>$value</em>"
                if (bold) value = "<strong>$value</strong>"
                out.append(value)
            }
        }
        fun headingLevel(paragraph: Element): Int? {
            val style = paragraph.kid("ppr")?.kid("pstyle")?.attr("w:val") ?: return null
            val name = styles[style].orEmpty().ifBlank { style.lowercase() }
            if (name == "title") return 1
            return Regex("heading\\s?(\\d)").find(name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6)
        }
        val body = document.allElements.firstOrNull { it.local() == "body" } ?: throw IllegalArgumentException("This Word document is empty.")
        val html = StringBuilder()
        var inList = false
        fun paragraph(p: Element, out: StringBuilder) {
            val content = StringBuilder().also { runs(p, it) }
            val level = headingLevel(p)
            val listed = p.kid("ppr")?.kid("numpr") != null
            if (listed && !inList) { out.append("<ul>"); inList = true }
            if (!listed && inList) { out.append("</ul>"); inList = false }
            when {
                level != null -> out.append("<h$level>").append(content).append("</h$level>")
                listed -> out.append("<li>").append(content).append("</li>")
                content.isEmpty() -> Unit
                else -> out.append("<p>").append(content).append("</p>")
            }
        }
        body.children().forEach { block ->
            when (block.local()) {
                "p" -> paragraph(block, html)
                "tbl" -> {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<table>")
                    block.kids("tr").forEach { row ->
                        html.append("<tr>")
                        row.kids("tc").forEach { cell -> html.append("<td>"); cell.kids("p").forEach { p -> StringBuilder().also { runs(p, it) }.let { if (it.isNotEmpty()) html.append("<p>").append(it).append("</p>") } }; html.append("</td>") }
                        html.append("</tr>")
                    }
                    html.append("</table>")
                }
            }
        }
        if (inList) html.append("</ul>")
        Book(title, listOfNotNull(author), chapters = ReadingConvert.splitAtHeadings(Jsoup.parseBodyFragment(html.toString()).body(), title), images = images.images)
    }

    // ---- RTF ------------------------------------------------------------------------------------------------------------------

    private val skipped = setOf("fonttbl", "colortbl", "stylesheet", "pict", "object", "header", "headerl", "headerr", "headerf", "footer", "footerl",
        "footerr", "footerf", "fldinst", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
        "datastore", "latentstyles", "pgdsctbl", "mmathpr", "bkmkstart", "bkmkend", "revtbl", "filetbl", "operator", "company", "keywords",
        "doccomm", "comment", "subject", "category", "manager", "creatim", "revtim", "printim", "buptim", "nonshppict", "shppict", "blipuid")

    /** RTF text: paragraphs, bold and italic, the document's title and author; pictures and tables of fonts are left out. */
    fun rtf(bytes: ByteArray, fallbackTitle: String): Book {
        val source = String(bytes, Charsets.ISO_8859_1)
        require(source.startsWith("{\\rtf")) { "This is not an RTF document." }
        class State(var skip: Boolean = false, var bold: Boolean = false, var italic: Boolean = false, var uc: Int = 1, var capture: String? = null)
        val stack = ArrayList<State>()
        var state = State()
        var charset: Charset = Charset.forName("windows-1252")
        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        val pendingBytes = java.io.ByteArrayOutputStream()
        val captured = HashMap<String, StringBuilder>()
        var openBold = false; var openItalic = false
        fun emitText(text: String) {
            if (text.isEmpty()) return
            if (state.skip) { state.capture?.let { captured.getOrPut(it) { StringBuilder() }.append(text) }; return }
            if (openItalic && !state.italic) { current.append("</em>"); openItalic = false }
            if (openBold && !state.bold) { if (openItalic) { current.append("</em>"); openItalic = false }; current.append("</strong>"); openBold = false }
            if (state.bold && !openBold) { current.append("<strong>"); openBold = true }
            if (state.italic && !openItalic) { current.append("<em>"); openItalic = true }
            current.append(escape(text))
        }
        fun flushBytes() { if (pendingBytes.size() > 0) { emitText(String(pendingBytes.toByteArray(), charset)); pendingBytes.reset() } }
        fun paragraph() {
            flushBytes()
            if (openItalic) current.append("</em>"); if (openBold) current.append("</strong>")
            openBold = false; openItalic = false
            val text = current.toString()
            if (Jsoup.parseBodyFragment(text).text().isNotBlank()) paragraphs += text
            current.clear()
        }
        var i = 0
        var skipChars = 0
        var groupStart = false
        while (i < source.length) {
            val c = source[i]
            when {
                c == '{' -> { flushBytes(); stack += State(state.skip, state.bold, state.italic, state.uc, state.capture); groupStart = true; i++ ; continue }
                c == '}' -> { flushBytes(); state = stack.removeLastOrNull() ?: State(); groupStart = false; i++; continue }
                c == '\\' && i + 1 < source.length -> {
                    val next = source[i + 1]
                    when {
                        next == '\'' && i + 3 < source.length -> {
                            if (skipChars > 0) skipChars-- else source.substring(i + 2, i + 4).toIntOrNull(16)?.let { if (!state.skip) pendingBytes.write(it) else emitText(it.toChar().toString()) }
                            i += 4; groupStart = false; continue
                        }
                        next == '*' -> { state.skip = true; i += 2; continue }
                        next in "{}\\" -> { flushBytes(); emitText(next.toString()); i += 2; groupStart = false; continue }
                        next == '~' -> { flushBytes(); emitText(" "); i += 2; continue }
                        next == '_' -> { flushBytes(); emitText("‑"); i += 2; continue }
                        next == '-' -> { i += 2; continue }
                        next == '\n' || next == '\r' -> { paragraph(); i += 2; continue }
                        next.isLetter() -> {
                            var end = i + 1
                            while (end < source.length && source[end].isLetter() && end - i < 33) end++
                            val word = source.substring(i + 1, end).lowercase()
                            var numberEnd = end
                            if (numberEnd < source.length && (source[numberEnd] == '-' || source[numberEnd].isDigit())) {
                                numberEnd++
                                while (numberEnd < source.length && source[numberEnd].isDigit() && numberEnd - end < 11) numberEnd++
                            }
                            val parameter = source.substring(end, numberEnd).toIntOrNull()
                            i = numberEnd
                            if (i < source.length && source[i] == ' ') i++
                            val first = groupStart
                            groupStart = false
                            if (word != "u" && word != "uc") flushBytes()
                            when (word) {
                                "par", "sect", "page" -> paragraph()
                                "line" -> emitText("\n")
                                "tab" -> emitText(" ")
                                "b" -> state.bold = parameter != 0
                                "i" -> state.italic = parameter != 0
                                "plain" -> { state.bold = false; state.italic = false }
                                "uc" -> state.uc = parameter ?: 1
                                "u" -> { flushBytes(); parameter?.let { emitText((if (it < 0) it + 65536 else it).toChar().toString()) }; skipChars = state.uc }
                                "ansicpg" -> parameter?.let { page -> runCatching { Charset.forName(if (page == 932) "Shift_JIS" else "windows-$page") }.getOrNull()?.let { charset = it } }
                                "emdash" -> emitText("—"); "endash" -> emitText("–"); "bullet" -> emitText("•")
                                "lquote" -> emitText("‘"); "rquote" -> emitText("’"); "ldblquote" -> emitText("“"); "rdblquote" -> emitText("”")
                                "title", "author" -> if (first) { state.skip = true; state.capture = word }
                                "info" -> if (first) state.skip = true
                                else -> if (first && word in skipped) state.skip = true
                            }
                            continue
                        }
                        else -> { i += 2; continue }
                    }
                }
                c == '\r' || c == '\n' -> { i++; continue }
                else -> {
                    groupStart = false
                    if (skipChars > 0) { skipChars--; i++; continue }
                    flushBytes()
                    var end = i
                    while (end < source.length && source[end] !in "{}\\\r\n") end++
                    emitText(source.substring(i, end))
                    i = end
                }
            }
        }
        paragraph()
        val title = captured["title"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val parts = paragraphs.chunked(250)
        val chapters = parts.mapIndexed { index, part ->
            Chapter(if (parts.size == 1) title else "Part ${index + 1}", part.joinToString("") { "<p>" + it.replace("\n", "<br/>") + "</p>" })
        }
        return Book(title, listOfNotNull(captured["author"]?.toString()?.trim()?.takeIf { it.isNotBlank() }), chapters = chapters)
    }
}
