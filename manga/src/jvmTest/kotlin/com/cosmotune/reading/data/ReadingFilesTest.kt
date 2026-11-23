package com.cosmotune.reading.data

import com.cosmotune.reading.ui.historyGroup
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReadingFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aBookFileSaysItsTitleAndAuthorWithoutBeingImported() {
        val epub = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf("mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                    "content.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>The Sea</dc:title><dc:creator>A. Writer</dc:creator></metadata></package>""")
                    .forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
            output.toByteArray()
        }
        assertEquals(ReadingFileInfo("The Sea", "A. Writer", "EPUB"), ReadingFilePeek.peek("sea.epub") { epub.inputStream() })
        val fb2 = """<FictionBook><description><title-info><author><first-name>Anna</first-name><last-name>Lee</last-name></author><book-title>Rain</book-title></title-info></description></FictionBook>"""
        assertEquals(ReadingFileInfo("Rain", "Anna Lee", "FB2"), ReadingFilePeek.peek("rain.fb2") { fb2.byteInputStream() })
        assertEquals(ReadingFileInfo("my notes", "", "PDF"), ReadingFilePeek.peek("my_notes.pdf") { ByteArray(0).inputStream() })
        assertEquals("EPUB", ReadingFilePeek.peek("broken.epub") { "not a zip".byteInputStream() }.format, "a damaged file goes by its name")
        assertTrue(ReadingFilePeek.isBook("x.AZW3")); assertFalse(ReadingFilePeek.isBook("x.jpg"))
    }

    @Test fun historyIsGroupedByDay() {
        val today = LocalDate.of(2026, 11, 23)
        fun at(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("Today", historyGroup(at(today), today))
        assertEquals("Yesterday", historyGroup(at(today.minusDays(1)), today))
        assertEquals("This week", historyGroup(at(today.minusDays(4)), today))
        assertEquals("This month", historyGroup(at(today.minusDays(20)), today))
        assertEquals("Earlier", historyGroup(at(today.minusDays(90)), today))
    }
}
