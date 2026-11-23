package com.cosmotune.manga.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MangaLibraryQueryTest {
    private val sources = mapOf("md" to "MangaDex", "local" to "Imported")
    private val berserk = MangaBook("md:1", "md", "Berserk", authors = listOf("Kentaro Miura"), tags = listOf("Action", "Dark Fantasy"),
        parserData = MangaParserBookData("https://x", alternateTitles = setOf("ベルセルク")))
    private val yotsuba = MangaBook("md:2", "md", "Yotsuba&!", authors = listOf("Kiyohiko Azuma"), tags = listOf("Comedy", "Slice of Life"))
    private val local = MangaBook("local:3", "local", "Dark Souls Comic", authors = listOf("Someone"), tags = listOf("Action"))
    private val all = listOf(berserk, yotsuba, local)

    private fun find(text: String) = MangaLibraryQuery.parse(text).filter(all, sources).map { it.title }

    @Test fun plainWordsMatchTitlesAlternateTitlesAndAuthors() {
        assertEquals(listOf("Berserk"), find("bers"))
        assertEquals(listOf("Berserk"), find("ベルセルク"))
        assertEquals(listOf("Yotsuba&!"), find("azuma"))
        assertEquals(all.map { it.title }, find(""))
    }

    @Test fun fieldsNarrowToOnePart() {
        assertEquals(listOf("Berserk", "Dark Souls Comic"), find("genre:action"))
        assertEquals(listOf("Berserk"), find("genre:\"dark fantasy\""))
        assertEquals(listOf("Dark Souls Comic"), find("source:imported"))
        assertEquals(listOf("Yotsuba&!"), find("author:kiyohiko"))
        assertEquals(listOf("Dark Souls Comic"), find("title:dark"))
    }

    @Test fun aMinusLeavesOutAndSpacesMeanAll() {
        assertEquals(listOf("Dark Souls Comic"), find("genre:action -berserk"))
        assertEquals(listOf("Berserk"), find("genre:action source:mangadex"))
        assertEquals(listOf("Yotsuba&!"), find("-genre:action"))
    }

    @Test fun aBarMeansEither() {
        assertEquals(listOf("Berserk", "Yotsuba&!"), find("genre:comedy|\"dark fantasy\""))
        assertEquals(listOf("Berserk", "Yotsuba&!"), find("berserk | yotsuba"))
    }

    @Test fun anUnknownFieldIsReadAsPlainText() {
        assertEquals(emptyList(), find("year:1989"))
        assertEquals(listOf("Yotsuba&!"), find("yotsuba&!"))
    }

    @Test fun sourcesSearchThePlainWordsOnly() {
        assertEquals("berserk dark fantasy", MangaLibraryQuery.plainWords("berserk genre:action -old \"dark fantasy\" source:md"))
        assertEquals("year:1989", MangaLibraryQuery.plainWords("year:1989"))
    }
}
