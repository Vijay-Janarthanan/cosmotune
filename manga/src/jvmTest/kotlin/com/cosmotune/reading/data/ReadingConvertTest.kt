package com.cosmotune.reading.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingConvertTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)

    private fun file(name: String, bytes: ByteArray) = File(temporary.root, name).apply { writeBytes(bytes) }
    private fun zip(entries: Map<String, ByteArray>) = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        output.toByteArray()
    }
    private fun text(book: ReadingConvert.Book) = book.chapters.joinToString("\n") { Jsoup.parse(it.html).text() }

    @Test fun plainTextFindsChaptersParagraphsAndOldEncodings() {
        val source = "CHAPTER I\n\nIt was a bright\ncold day.\n\nThe clocks struck.\n\nCHAPTER II\n\nWinston went home.\n"
        val book = ReadingConvert.text(source, "Nineteen")
        assertEquals(listOf("CHAPTER I", "CHAPTER II"), book.chapters.map { it.title })
        assertTrue("<p>It was a bright cold day.</p>" in book.chapters[0].html)
        assertEquals("café", ReadingConvert.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
        assertEquals("café", ReadingConvert.decode("café".toByteArray()))
        assertEquals("<b>", Jsoup.parse(ReadingConvert.text("<b>", "x").chapters.single().html).text())
    }

    @Test fun markdownKeepsHeadingsEmphasisListsAndCode() {
        val book = ReadingConvert.markdown("# One\n\nSome *soft* and **bold** words with `code`.\n\n- a\n- b\n\n# Two\n\n> quoted\n\n```\nx < y\n```\n", "Notes")
        assertEquals("One", book.title)
        assertEquals(listOf("One", "Two"), book.chapters.map { it.title })
        assertTrue("<em>soft</em>" in book.chapters[0].html && "<strong>bold</strong>" in book.chapters[0].html && "<li>a</li>" in book.chapters[0].html)
        assertTrue("<blockquote>" in book.chapters[1].html && "x &lt; y" in book.chapters[1].html)
    }

    @Test fun htmlAndSavedPagesKeepTheirOwnPicturesOnly() {
        val data = Base64.getEncoder().encodeToString(png)
        val page = """<html><head><title>Saved story</title><meta name="author" content="Ana"></head><body><h1>First</h1><p>One <a href="https://x.invalid">link</a></p><img src="data:image/png;base64,$data"><img src="https://tracker.invalid/p.gif"><h1>Second</h1><p>Two</p><script>alert(1)</script></body></html>"""
        val book = ReadingConvert.html(Jsoup.parse(page), "x", ReadingConvert.ImageSink())
        assertEquals("Saved story", book.title)
        assertEquals(listOf("Ana"), book.authors)
        assertEquals(1, book.images.size)
        assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
        assertFalse("tracker" in book.chapters[0].html || "alert" in book.chapters.joinToString { it.html } || "href" in book.chapters[0].html)
        val mhtml = "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=\"B\"\r\n\r\n--B\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\nContent-Location: https://site.invalid/a.html\r\n\r\n<html><body><p>Caf=C3=A9 =\r\nnotes</p><img src=3D\"pic.png\"></body></html>\r\n--B\r\nContent-Type: image/png\r\nContent-Transfer-Encoding: base64\r\nContent-Location: https://site.invalid/pic.png\r\n\r\n$data\r\n--B--\r\n"
        val saved = ReadingConvert.mhtml(mhtml.toByteArray(Charsets.ISO_8859_1), "Page")
        assertEquals("Café notes", text(saved))
        assertEquals(1, saved.images.size)
    }

    @Test fun fictionBookKeepsSectionsNotesCoverAndAuthors() {
        val data = Base64.getEncoder().encodeToString(png)
        val fb2 = """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><author><first-name>Lev</first-name><last-name>Tolstoy</last-name></author><book-title>Short stories</book-title><lang>ru</lang>
            <coverpage><image l:href="#cover.png"/></coverpage></title-info></description>
            <body><section><title><p>The first</p></title><p>Text with <emphasis>stress</emphasis><a l:href="#n1" type="note">1</a>.</p></section>
            <section><title><p>The second</p></title><poem><stanza><v>A line</v></stanza></poem></section></body>
            <body name="notes"><section id="n1"><p>A note.</p></section></body>
            <binary id="cover.png" content-type="image/png">$data</binary></FictionBook>"""
        val book = ReadingConvert.convert("fb2", file("s.fb2", fb2.toByteArray()), "x")
        assertEquals("Short stories", book.title)
        assertEquals(listOf("Lev Tolstoy"), book.authors)
        assertEquals("ru", book.language)
        assertEquals(listOf("The first", "The second", "Notes"), book.chapters.map { it.title })
        assertNotNull(book.cover)
        assertTrue("c0003.xhtml#n1" in book.chapters[0].html && "<em>stress</em>" in book.chapters[0].html)
        assertTrue("id=\"n1\"" in book.chapters[2].html)
    }

    @Test fun wordDocumentsKeepHeadingsBoldTablesAndPictures() {
        val document = """<w:document xmlns:w="w" xmlns:r="r" xmlns:a="a"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Start</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:t xml:space="preserve"> plain</w:t></w:r></w:p>
            <w:p><w:r><w:drawing><a:blip r:embed="rId5"/></w:drawing></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>End</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""
        val docx = zip(mapOf("word/document.xml" to document.toByteArray(),
            "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="rId5" Target="media/image1.png"/></Relationships>""".toByteArray(),
            "word/media/image1.png" to png,
            "docProps/core.xml" to """<cp:coreProperties xmlns:cp="c" xmlns:dc="d"><dc:title>Report</dc:title><dc:creator>Mina</dc:creator></cp:coreProperties>""".toByteArray()))
        val book = ReadingConvert.convert("docx", file("r.docx", docx), "x")
        assertEquals("Report", book.title)
        assertEquals(listOf("Mina"), book.authors)
        assertEquals(listOf("Start", "End"), book.chapters.map { it.title })
        assertTrue("<strong>Bold</strong> plain" in book.chapters[0].html)
        assertEquals(1, book.images.size)
        assertTrue("<td>" in book.chapters[1].html)
    }

    @Test fun rtfKeepsParagraphsStylesAndSpecialCharacters() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl{\f0 Times;}}{\info{\title Letters}{\author Jo}}\f0 Dear {\b friend},\par Caf\'e9 and EURO? and \ldblquote quotes\rdblquote.\par}""".replace("EURO", "\\" + "u8364")
        val book = ReadingConvert.convert("rtf", file("l.rtf", rtf.toByteArray()), "x")
        assertEquals("Letters", book.title)
        assertEquals(listOf("Jo"), book.authors)
        assertEquals("Dear friend, Café and € and “quotes”.", text(book))
        assertTrue("<strong>friend</strong>" in book.chapters.single().html)
        assertFalse("Times" in text(book))
    }

    @Test fun palmDocAndTrailingEntriesDecodeAsKindleWritesThem() {
        val compressed = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), 0x18, 0xC1.toByte(), 0x02, 0xF0.toByte(), 0x01)
        assertContentEquals("abcabc A".toByteArray() + byteArrayOf(0xF0.toByte(), 0x01), ReadingMobi.palmDoc(compressed))
        assertEquals(3, ReadingMobi.trailing("hello".toByteArray() + byteArrayOf(1, 2, 0x83.toByte()), 0b10))
        assertEquals(2, ReadingMobi.trailing("hello".toByteArray() + byteArrayOf(0, 1), 0b1))
    }

    private fun mobi(html: String, encryption: Int = 0): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val text = html.toByteArray()
        val exthRecords = listOf(100 to "Mary Shelley", 503 to "Frankenstein").map { (type, value) -> u32(type) + u32(value.toByteArray().size + 8) + value.toByteArray() }
        val exth = "EXTH".toByteArray() + u32(12 + exthRecords.sumOf { it.size }) + u32(exthRecords.size) + exthRecords.reduce { a, b -> a + b }
        val mobiHeader = ByteArray(232).also { h ->
            "MOBI".toByteArray().copyInto(h, 0); u32(232).copyInto(h, 4); u32(2).copyInto(h, 8); u32(65001).copyInto(h, 12); u32(6).copyInto(h, 20)
            u32(2).copyInto(h, 92); u32(0x40).copyInto(h, 112)
        }
        val name = "Frankenstein".toByteArray()
        val record0Body = u16(1) + u16(0) + u32(text.size) + u16(1) + u16(4096) + u16(encryption) + u16(0) + mobiHeader + exth
        val record0 = record0Body.copyOf().also { u32(record0Body.size).copyInto(it, 16 + 68); u32(name.size).copyInto(it, 16 + 72) } + name
        val records = listOf(record0, text, png)
        val header = ByteArray(78).also { h -> "Frankenstein".toByteArray().copyInto(h); "BOOKMOBI".toByteArray().copyInto(h, 60); u16(records.size).copyInto(h, 76) }
        var offset = 78 + records.size * 8 + 2
        val list = records.mapIndexed { index, record -> (u32(offset) + u32(index)).also { offset += record.size } }.reduce { a, b -> a + b }
        return header + list + byteArrayOf(0, 0) + records.reduce { a, b -> a + b }
    }

    @Test fun mobiBooksKeepChaptersTitleAuthorAndPicturesAndRefuseDrm() {
        val book = ReadingConvert.convert("mobi", file("f.mobi", mobi("<html><body><h2>Letter 1</h2><p>You will rejoice.</p><img recindex=\"00001\"><mbp:pagebreak/><h2>Letter 2</h2><p>How slowly the time passes.</p></body></html>")), "x")
        assertEquals("Frankenstein", book.title)
        assertEquals(listOf("Mary Shelley"), book.authors)
        assertEquals(listOf("Letter 1", "Letter 2"), book.chapters.map { it.title })
        assertEquals(1, book.images.size)
        assertTrue("images/${book.images.single().name}" in book.chapters[0].html)
        val error = assertFailsWith<IllegalArgumentException> { ReadingConvert.convert("mobi", file("d.mobi", mobi("<p>x</p>", encryption = 2)), "x") }
        assertTrue("DRM" in error.message.orEmpty())
    }

    @Test fun comicArchivesBecomePictureBooksInNaturalOrder() {
        val cbz = zip(mapOf("page10.png" to png, "page2.png" to png + byteArrayOf(1), "notes.txt" to "x".toByteArray()))
        val book = ReadingConvert.convert("cbz", file("c.cbz", cbz), "Comic")
        assertEquals(2, book.chapters.size)
        assertTrue(book.pictures)
        assertTrue(ReadingConvert.natural("page2.png", "page10.png") < 0)
    }

    @Test fun convertedBooksImportAsEpubsKeepingTheirOriginalIdentity() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val repository = ReadingRepository(root)
        val source = "Chapter 1\n\nOnce upon a time.\n\nChapter 2\n\nThe end.\n".toByteArray()
        val book = repository.importFile("Tale.txt") { source.inputStream() }
        assertEquals(ReadingFormat.EPUB, book.format)
        assertEquals("TXT", book.originalFormat)
        assertEquals("Tale", book.title)
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }, book.id)
        val document = assertIs<ReadingDocument.Epub>(ReadingRepository(root).openDocument(book.id))
        assertEquals(listOf("Chapter 1", "Chapter 2"), document.navigation.map { it.title })
        assertEquals(book.id, repository.importFile("Again.txt") { source.inputStream() }.id)
        assertEquals(1, repository.state.value.books.size)
        assertFailsWith<IllegalArgumentException> { repository.importFile("movie.mkv") { source.inputStream() } }
    }
}
