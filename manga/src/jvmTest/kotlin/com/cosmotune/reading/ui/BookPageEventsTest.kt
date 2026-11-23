package com.cosmotune.reading.ui

import com.cosmotune.reading.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class BookPageEventsTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun fb2Book(): ReadingDocument.Epub {
        val repository = ReadingRepository(temporary.newFolder())
        val fb2 = """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Notes</book-title></title-info></description>
            <body><section><title><p>One</p></title><p>Text<a l:href="#n1" type="note">1</a>.</p></section><section><title><p>Two</p></title><p>More.</p></section></body>
            <body name="notes"><section id="n1"><p>A note.</p></section></body></FictionBook>"""
        val book = repository.importFile("n.fb2") { fb2.byteInputStream() }
        return assertIs(repository.openDocument(book.id))
    }

    @Test fun notesInOtherChaptersArePickedOutAndPicturesFoundByAddress() = runBlocking<Unit> {
        val document = fb2Book()
        val notes = document.spine.last().href
        assertEquals("A note.", readingNoteText(document, "https://cosmotune-publication.invalid/$notes#n1"))
        assertNull(readingNoteText(document, "https://cosmotune-publication.invalid/$notes#missing"))
        assertEquals(document.resources.getValue(notes).file, readingResourceFile(document, "http://127.0.0.1:4000/token/$notes"))
        assertNull(readingResourceFile(document, "https://elsewhere.invalid/secret.txt"))
    }

    @Test fun pageEventsCarrySelectionsNotesAndPictures() {
        val event = parsePageEvent("""{"tapped":null,"note":{"text":"See page 4."},"image":null,"text":"word","start":3,"end":7,"progression":0.5}""")
        assertEquals(ReadingSelection("word", 3, 7, .5), event.selection)
        assertEquals("See page 4.", event.noteText)
        assertNull(event.image)
        assertEquals("https://x/y.png", parsePageEvent("""{"image":"https://x/y.png"}""").image)
        assertEquals(PageEvent(null, null), parsePageEvent(""))
    }

    @Test fun contentsKeepTheirDepth() {
        val navigation = listOf(ReadingNavigation("Part", "a", listOf(ReadingNavigation("Chapter", "b", listOf(ReadingNavigation("Section", "c"))))), ReadingNavigation("End", "d"))
        assertEquals(listOf("Part" to 0, "Chapter" to 1, "Section" to 2, "End" to 0), navigation.nestedNavigation().map { it.first.title to it.second })
    }

    @Test fun rightToLeftBooksFixedLayoutPagesNoteMarksAndFormulasSurviveTheCleaning() = runBlocking<Unit> {
        val document = fb2Book().let { it.copy(book = it.book.copy(language = "ar")) }
        val content = ReadingEpubContent(document, "https://cosmotune-publication.invalid/")
        val html = content.html(document.spine.first().href, """<html><head><meta name="viewport" content="width=600, height=800"/></head><body>
            <a epub:type="noteref" href="#n">1</a><math><mi mathvariant="bold">x</mi></math></body></html>""")
        assertTrue("dir=\"rtl\"" in html && "data-cosmo-fixed=\"600,800\"" in html)
        assertTrue("epub:type=\"noteref\"" in html && "mathvariant=\"bold\"" in html)
    }
}
