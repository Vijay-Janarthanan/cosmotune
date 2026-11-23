package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingHighlightsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun highlightsKeepNotesReplaceOverlapsFollowTheBookAndExportInBookOrder() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val repository = ReadingRepository(root)
        val book = repository.importFile("Tale.txt") { "Chapter 1\n\nOnce upon a time.\n\nChapter 2\n\nThe end.\n".byteInputStream() }
        val later = repository.saveHighlight(book.id, "OEBPS/c0002.xhtml", 0, 4, "The end", ReadingMarkStyle.BLUE, 0.1, section = 1, chapter = "Chapter 2")
        val first = repository.saveHighlight(book.id, "OEBPS/c0001.xhtml", 5, 9, "upon", ReadingMarkStyle.YELLOW, 0.2, section = 0, chapter = "Chapter 1")
        // A new highlight over the same words takes the old one's place.
        val replaced = repository.saveHighlight(book.id, "OEBPS/c0001.xhtml", 0, 9, "Once upon", ReadingMarkStyle.GREEN, 0.2, section = 0, chapter = "Chapter 1")
        assertEquals(listOf(replaced.id, later.id), ReadingNotesExport.highlights(repository.state.value, book.id).map { it.id })
        assertFalse(repository.state.value.highlights.any { it.id == first.id })
        repository.updateHighlight(replaced.id, style = ReadingMarkStyle.UNDERLINE, note = "  A good start  ")
        val restored = ReadingRepository(root).state.value.highlights.first { it.id == replaced.id }
        assertEquals(ReadingMarkStyle.UNDERLINE, restored.style)
        assertEquals("A good start", restored.note)
        val markdown = ReadingNotesExport.markdown(book, repository.state.value)
        assertTrue(markdown.indexOf("## Chapter 1") < markdown.indexOf("## Chapter 2"))
        assertTrue("> Once upon\n\nA good start" in markdown)
        val html = ReadingNotesExport.html(book.copy(title = "<Tale>"), repository.state.value)
        assertTrue("&lt;Tale&gt;" in html && "class=\"underline\"" in html)
        assertEquals("Tale notes.md", ReadingNotesExport.fileName(book.copy(title = "Tale/:"), "md"))
        repository.removeHighlight(later.id)
        repository.restoreHighlight(later)
        assertEquals(2, repository.state.value.highlights.size)
        val taken = assertNotNull(repository.takeOutBook(book.id))
        assertTrue(repository.state.value.highlights.isEmpty())
        repository.putBackBook(taken)
        assertEquals(2, repository.state.value.highlights.size)
        assertFailsWith<IllegalArgumentException> { repository.saveHighlight(book.id, "x", 4, 4, "", ReadingMarkStyle.YELLOW, 0.0) }
        repository.removeBook(book.id)
        assertTrue(repository.state.value.highlights.isEmpty())
    }
}
