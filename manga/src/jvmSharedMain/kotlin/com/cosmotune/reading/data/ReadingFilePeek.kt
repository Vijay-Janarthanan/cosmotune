package com.cosmotune.reading.data

import java.io.InputStream
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** What a book file says about itself, read from its first bytes without importing it: for the file browser. */
data class ReadingFileInfo(val title: String, val author: String, val format: String)

internal object ReadingFilePeek {
    private val formats = mapOf("epub" to "EPUB", "pdf" to "PDF", "mobi" to "Kindle", "azw" to "Kindle", "azw3" to "Kindle", "prc" to "Kindle",
        "fb2" to "FB2", "fbz" to "FB2", "txt" to "Text", "md" to "Markdown", "markdown" to "Markdown", "html" to "Web page", "htm" to "Web page",
        "mht" to "Web page", "mhtml" to "Web page", "docx" to "Word", "rtf" to "RTF", "cbz" to "Comic", "chm" to "Help book")

    /** Whether the file browser lists [name] as a book. */
    fun isBook(name: String) = name.substringAfterLast('.', "").lowercase() in formats

    /** The title and author [name]'s file gives (EPUB, FB2 and Kindle files say; others go by their file name). */
    fun peek(name: String, open: () -> InputStream): ReadingFileInfo {
        val extension = name.substringAfterLast('.', "").lowercase()
        val fallback = ReadingFileInfo(name.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { name }, "", formats[extension] ?: extension.uppercase())
        return runCatching {
            when (extension) {
                "epub" -> epub(open)?.let { (title, author) -> fallback.copy(title = title.ifBlank { fallback.title }, author = author) }
                "fb2" -> open().use { input -> fb2(input.readNBytesCompat(256 * 1024).decodeToString()) }?.let { (title, author) -> fallback.copy(title = title.ifBlank { fallback.title }, author = author) }
                "mobi", "azw", "azw3", "prc" -> open().use { input -> mobi(input.readNBytesCompat(64 * 1024)) }?.let { fallback.copy(title = it) }
                else -> null
            }
        }.getOrNull() ?: fallback
    }

    /** An EPUB's title and first creator: the container names the package file, read on a second pass. */
    private fun epub(open: () -> InputStream): Pair<String, String>? {
        var opfPath: String? = null
        var opf: String? = null
        fun pass(wanted: (String) -> Boolean, take: (String, String) -> Unit) = ZipInputStream(open()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > 5_000) break
                if (!entry.isDirectory && wanted(entry.name)) { take(entry.name, zip.readNBytesCompat(1024 * 1024).decodeToString()); return@use }
            }
        }
        pass({ it == "META-INF/container.xml" }) { _, text ->
            opfPath = Jsoup.parse(text, "", Parser.xmlParser()).getAllElements().firstOrNull { it.tagName().substringAfter(':') == "rootfile" }?.attr("full-path")
        }
        val path = opfPath ?: return null
        pass({ it == path }) { _, text -> opf = text }
        val document = Jsoup.parse(opf ?: return null, "", Parser.xmlParser())
        fun first(local: String) = document.getAllElements().firstOrNull { it.tagName().substringAfter(':') == local }?.text()?.trim().orEmpty()
        return first("title").take(300) to first("creator").take(200)
    }

    private fun fb2(text: String): Pair<String, String>? {
        val document = Jsoup.parse(text, "", Parser.xmlParser())
        val info = document.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "title-info" } ?: return null
        fun child(parent: org.jsoup.nodes.Element?, local: String) = parent?.children()?.firstOrNull { it.tagName().substringAfter(':') == local }
        val author = child(info, "author")
        val name = listOf("first-name", "middle-name", "last-name").mapNotNull { child(author, it)?.text()?.trim()?.ifBlank { null } }.joinToString(" ")
        return child(info, "book-title")?.text()?.trim().orEmpty().take(300) to name.take(200)
    }

    /** A Kindle file's full name from its MOBI header, or the database name in its first 32 bytes. */
    private fun mobi(bytes: ByteArray): String? {
        if (bytes.size < 78) return null
        fun int(at: Int) = if (at + 4 > bytes.size) -1 else ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
        val record0 = int(78)
        if (record0 in 0 until bytes.size - 92 && String(bytes, record0 + 16, 4, Charsets.ISO_8859_1) == "MOBI") {
            val offset = int(record0 + 84)
            val length = int(record0 + 88)
            if (offset >= 0 && length in 1..1000 && record0 + offset + length <= bytes.size)
                return String(bytes, record0 + offset, length, Charsets.UTF_8).trim().ifBlank { null }
        }
        return String(bytes, 0, 32, Charsets.ISO_8859_1).substringBefore('\u0000').replace('_', ' ').trim().ifBlank { null }
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
