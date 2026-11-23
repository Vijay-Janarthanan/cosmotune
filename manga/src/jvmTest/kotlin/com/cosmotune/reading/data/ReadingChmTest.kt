package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

/** The sample is Apache Tika's own CHM test document (Apache-2.0), kept in the test resources. */
class ReadingChmTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sample(): File = File(temporary.newFolder(), "testChm.chm").apply {
        writeBytes(ReadingChmTest::class.java.getResourceAsStream("/chm/testChm.chm")!!.use { it.readBytes() })
    }

    @Test fun contentsGiveTheTopicsInOrderWithTheirPages() {
        val hhc = """<html><body><ul>
            <li><object type="text/sitemap"><param name="Name" value="Welcome"><param name="Local" value="html/intro.htm"></object>
            <li><object type="text/sitemap"><param name="Name" value="Setting up"><param name="Local" value="Guide.chm::/html/Setup%20Steps.htm#first"></object>
            <li><object type="text/sitemap"><param name="Name" value="Website"><param name="Local" value="https://example.org/"></object>
            </ul></body></html>"""
        assertEquals(listOf("Welcome" to "/html/intro.htm", "Setting up" to "/html/setup steps.htm"), ReadingChm.toc(hhc))
        assertEquals("/images/logo.gif", ReadingChm.resolve("/html/guide", "../../images/Logo.gif"))
        assertEquals("/html/pic.png", ReadingChm.resolve("/html", "pic.png#x"))
    }

    @Test fun aHelpBookBecomesChaptersAndOpensAsABook() = runBlocking<Unit> {
        val file = sample()
        val book = ReadingChm.convert(file, "testChm")
        assertTrue(book.chapters.isNotEmpty(), "no chapters")
        assertTrue(book.chapters.sumOf { it.html.length } > 200, "too little text")
        val repository = ReadingRepository(temporary.newFolder())
        val imported = repository.importFile("testChm.chm") { file.inputStream() }
        assertEquals(ReadingFormat.EPUB, imported.format)
    }

    @Test fun somethingElseNamedChmIsRefusedPlainly() {
        val file = File(temporary.newFolder(), "fake.chm").apply { writeText("not a help book") }
        val failure = assertFailsWith<IllegalArgumentException> { ReadingChm.convert(file, "fake") }
        assertContains(failure.message.orEmpty(), "CHM")
    }
}
