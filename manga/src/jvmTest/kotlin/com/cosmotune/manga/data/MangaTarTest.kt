package com.cosmotune.manga.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaTarTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun tar(vararg entries: Triple<String, ByteArray, Char>): ByteArray = ByteArrayOutputStream().apply {
        for ((name, content, type) in entries) {
            val header = ByteArray(512)
            name.toByteArray().copyInto(header)
            content.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
            header[156] = type.code.toByte()
            for (index in 148..155) header[index] = 32
            header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0').toByteArray().copyInto(header, 148)
            header[154] = 0; header[155] = 32
            write(header); write(content); write(ByteArray((512 - content.size % 512) % 512))
        }
        write(ByteArray(1024))
    }.toByteArray()

    @Test fun tarAndGzipComicsBecomeBoundedCbzChaptersInNaturalOrder() = runBlocking<Unit> {
        val archive = MangaArchive(temporary.newFolder())
        val raw = tar(Triple("./", byteArrayOf(), '5'), Triple("./chapter2/10.png", byteArrayOf(10), '0'), Triple("./chapter2/2.png", byteArrayOf(2), '0'), Triple("./chapter1/1.png", byteArrayOf(1), '0'))
        for (extension in listOf("cbt", "tar", "tgz", "tar.gz")) {
            val bytes = if (extension in listOf("tgz", "tar.gz")) ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(raw) } }.toByteArray() else raw
            val book = archive.import("Comic.$extension") { bytes.inputStream() }
            assertEquals(listOf("chapter1", "chapter2"), book.chapters.map { it.title })
            val pages = archive.chapterPages(book.chapters[1])
            assertEquals(listOf("chapter2/2.png", "chapter2/10.png"), pages.map { it.archiveEntry })
            assertContentEquals(byteArrayOf(2), archive.pageFile(pages[0]).readBytes())
        }
    }
    @Test fun traversalLinksBrokenHeadersAndUnknownFormatsFailWithoutExtractingFiles() = runBlocking<Unit> {
        val archive = MangaArchive(temporary.newFolder())
        for ((name, type) in listOf("../escape.png" to '0', "/absolute.png" to '0', "link.png" to '2', "extended.png" to 'x')) {
            val bytes = tar(Triple(name, byteArrayOf(1), type))
            assertFailsWith<IllegalArgumentException> { archive.import("bad.tar") { bytes.inputStream() } }
        }
        val damaged = tar(Triple("page.png", byteArrayOf(1), '0')).also { it[0] = 127 }
        assertFailsWith<IllegalArgumentException> { archive.import("bad.cbt") { damaged.inputStream() } }
        assertFailsWith<IllegalArgumentException> { archive.import("book.cbr") { ByteArray(0).inputStream() } }
        assertTrue(temporary.root.walkTopDown().none { it.name == "escape.png" })
    }
}
