package com.cosmotune.manga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikisourceNovelSourceTest {
    private val config = WikisourceNovelSource.config("en")

    @Test fun searchResultsBecomeSeriesAndSubpagesAreLeftOut() {
        val body = Json.parseToJsonElement("""
            {"continue":{"sroffset":20},"query":{"search":[
              {"ns":0,"title":"Treasure Island (1883)","pageid":3414},
              {"ns":0,"title":"Treasure Island (1883)/Chapter 1","pageid":1},
              {"ns":0,"title":"Lapsus Calami/On Treasure Island","pageid":2},
              {"ns":0,"title":"Kidnapped (Stevenson)","pageid":5}
            ]}}
        """.trimIndent()) as JsonObject
        val page = WikisourceNovelSource.searchPage(config, body)
        assertEquals(listOf("Treasure Island (1883)", "Kidnapped (Stevenson)"), page.books.map { it.title })
        assertEquals("https://en.wikisource.org/wiki/Treasure_Island_(1883)", page.books[0].url)
        assertEquals("wikisource:en", page.books[0].sourceId)
        assertEquals(20, page.nextOffset)
        assertNull(WikisourceNovelSource.searchPage(config, Json.parseToJsonElement("""{"query":{"search":[]}}""") as JsonObject).nextOffset)
    }

    @Test fun theContentsPageGivesTheChaptersInItsOwnOrder() {
        val html = """
            <div class="wst-header"><a href="/wiki/Author:Robert_Louis_Stevenson">Robert Louis Stevenson</a></div>
            <p><a href="/wiki/Treasure_Island_(1883)/Chapter_1">The Old Sea-dog at the Admiral Benbow</a></p>
            <p><a href="/wiki/Treasure_Island_(1883)/Chapter_2">Black Dog Appears</a>
               <a href="/wiki/Treasure_Island_(1883)/Chapter_1#p3">again</a></p>
            <p><a href="/wiki/Treasure_Island_(1883)/Chapter_10">The Voyage</a></p>
            <p><a href="/wiki/Treasure_Island_(1909)">Another edition</a> <a href="/wiki/Treasure_Island_(1883)/Chapter_3"></a></p>
            <p><a href="/w/index.php?title=Treasure_Island_(1883)/Chapter_4&amp;action=edit">edit</a></p>
        """.trimIndent()
        val chapters = WikisourceNovelSource.chapterLinks("Treasure Island (1883)", html)
        assertEquals(listOf("Treasure Island (1883)/Chapter 1", "Treasure Island (1883)/Chapter 2", "Treasure Island (1883)/Chapter 10",
            "Treasure Island (1883)/Chapter 3"), chapters.map { it.page })
        assertEquals(listOf("The Old Sea-dog at the Admiral Benbow", "Black Dog Appears", "The Voyage", "Chapter 3"), chapters.map { it.title })
        assertEquals(listOf("Robert Louis Stevenson"), WikisourceNovelSource.authors(html))
    }

    @Test fun aWorkWithoutChapterPagesIsOneChapter() {
        val book = MangaBook("b", config.id, "A Short Story", "https://en.wikisource.org/wiki/A_Short_Story")
        val chapters = WikisourceNovelSource.chapters(config, book, "A Short Story", emptyList())
        assertEquals(1, chapters.size)
        assertTrue(chapters.single().text)
        assertEquals("A Short Story", chapters.single().title)
        val many = WikisourceNovelSource.chapters(config, book, "A Short Story", listOf(WikisourceNovelSource.Link("A Short Story/Part 1", "Part 1")))
        assertEquals(1f, many.single().number)
        assertEquals("https://en.wikisource.org/wiki/A_Short_Story/Part_1", many.single().url)
    }

    @Test fun theChapterKeepsTheStoryAndDropsTheHeaderAndPageNumbers() {
        val html = """
            <div class="mw-parser-output">
              <div class="ws-noexport"><style>.mw-parser-output .wst-header #ws-data{display:none}</style></div>
              <div class="wst-header ws-noexport noprint"><a href="/wiki/Treasure_Island_(1883)">contents</a> Chapter 2 →</div>
              <p><span class="pagenum ws-pagenum" id="15">[15]</span>SQUIRE Trelawney asked me to write it down.<sup class="reference">[1]</sup></p>
              <p>He was a tall man.</p>
              <ol class="references"><li>A note</li></ol>
            </div>
        """.trimIndent()
        val chapter = WikisourceNovelSource.chapterText("Chapter 1", html)
        assertEquals(listOf("SQUIRE Trelawney asked me to write it down.", "He was a tall man."), chapter.blocks.map { it.text })
    }

    @Test fun onlyWikisourceAddressesAreRead() {
        assertEquals("Treasure Island (1883)/Chapter 1", WikisourceNovelSource.pageTitle(config, "https://en.wikisource.org/wiki/Treasure_Island_(1883)/Chapter_1"))
        assertEquals("Ça ira", WikisourceNovelSource.pageTitle(config, "https://en.wikisource.org/wiki/%C3%87a_ira"))
        assertNull(WikisourceNovelSource.pageTitle(config, "https://example.org/wiki/Treasure_Island"))
        assertNull(WikisourceNovelSource.pageTitle(config, "https://fr.wikisource.org/wiki/Treasure_Island"))
    }
}
