package com.cosmotune.manga.data

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaOpdsFeedTest {
    private val chapter = MangaChapter("md:1:c1", "md:1", "md", "Chapter 1 & more", "u", number = 1f)
    private val other = MangaChapter("md:1:c2", "md:1", "md", "Chapter 2", "u", number = 2f)
    private val book = MangaBook("md:1", "md", "Tom & <Jerry>", chapters = listOf(chapter, other))
    private val bare = MangaBook("md:2", "md", "Nothing downloaded")
    private val state = MangaLibraryState(books = listOf(book, bare), downloads = mapOf(chapter.id to listOf(MangaPage("p", localFile = "x"))))

    private fun parse(text: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(text.byteInputStream())

    @Test fun theRootListsSeriesWithDownloadsAndIsValidXml() {
        val doc = parse(MangaOpdsFeed.root(state.books, state, "http://pc:1/opds/key", "2026-11-23T00:00:00Z"))
        val titles = doc.getElementsByTagName("entry").let { list -> (0 until list.length).map { list.item(it).firstChildNamed("title") } }
        assertEquals(listOf("Tom & <Jerry>"), titles)
        assertTrue(MangaOpdsFeed.root(state.books, state, "http://pc:1/opds/key", "t").contains("http://pc:1/opds/key/series/md%3A1"))
    }

    @Test fun aSeriesListsItsDownloadedChaptersAsArchives() {
        val text = MangaOpdsFeed.series(book, state, "http://pc:1/opds/key", "2026-11-23T00:00:00Z")
        val doc = parse(text)
        assertEquals(1, doc.getElementsByTagName("entry").length)
        assertTrue(text.contains("http://pc:1/opds/key/chapter/md%3A1%3Ac1.cbz"))
        assertTrue(text.contains("application/vnd.comicbook+zip"))
    }

    private fun org.w3c.dom.Node.firstChildNamed(name: String): String {
        val children = childNodes
        for (i in 0 until children.length) if (children.item(i).nodeName == name) return children.item(i).textContent
        return ""
    }
}
