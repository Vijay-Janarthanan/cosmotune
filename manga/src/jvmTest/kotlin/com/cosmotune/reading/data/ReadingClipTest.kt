package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jsoup.Jsoup
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingClipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pdfLinesReflowIntoParagraphsMendingBrokenWords() {
        val page = "It was the best of times, it was the worst\nof times, it was the age of wis-\ndom, it was the age of foolishness.\n\nA new paragraph starts here and\nruns on."
        assertEquals(listOf("It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness.", "A new paragraph starts here and runs on."),
            ReadingConvert.reflowLines(page))
    }

    @Test fun aWebPageKeepsItsArticleNotItsMenus() {
        val page = Jsoup.parse("<html><body><nav>Home Sports</nav><header>Site</header><article><h2>Story</h2><p>The words.</p></article><footer>Contact</footer></body></html>")
        val main = ReadingConvert.mainContent(page)
        assertEquals("Story The words.", main.text())
    }

    @Test fun pastedTextAndPdfTextBecomeBooks() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        val pasted = repository.importText("Shopping list", "Eggs.\n\nMilk.")
        assertEquals("Shopping list", pasted.title)
        val pdf = repository.importFile("Paper.pdf") { "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".byteInputStream() }
        val copy = repository.importPdfText(pdf.id, List(12) { "Page ${it + 1} text line one\nand two." })
        assertEquals("Paper (text)", copy.title)
        assertEquals("PDF text", copy.originalFormat)
        val document = assertIs<ReadingDocument.Epub>(repository.openDocument(copy.id))
        assertEquals(listOf("Pages 1–10", "Pages 11–12"), document.navigation.map { it.title })
        assertFailsWith<IllegalArgumentException> { repository.importWebPage("http://example.com/") }
    }
}
