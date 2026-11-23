package com.cosmotune.podio

import java.nio.file.Files
import kotlin.test.*

class PodioTagsTest {
    private val news = PodcastShow("n", "News Daily", "", "https://publisher.example/news")
    private val tech = PodcastShow("t", "Tech Talk", "", "https://publisher.example/tech")
    private fun entry(id: String) = PodioQueueEntry(PodcastEpisode(id, id, "Show", "https://publisher.example/$id.mp3"), news)

    @Test fun tagsAreCleanedListedAndFiltered() {
        var saved = PodioTags.set(PodioSaved(shows = listOf(news, tech)), "n", listOf("  Morning   news ", "morning news", "", "Daily"))
        assertEquals(setOf("Morning news", "Daily"), saved.showTags.getValue("n"))
        saved = PodioTags.set(saved, "t", listOf("daily"))
        assertEquals(listOf("Daily", "Morning news"), PodioTags.all(saved))
        assertEquals(listOf("n", "t"), PodioTags.shows(saved, "DAILY").map { it.id })
        assertFalse("n" in PodioTags.set(saved, "n", emptyList()).showTags)
    }

    @Test fun opmlFoldersBecomeTagsAndTagsBecomeFolders() {
        val file = Files.createTempDirectory("podio-tags").resolve("library.json").toFile()
        val library = PodioLibrary(file)
        val xml = """<?xml version="1.0"?><opml version="2.0"><body>
            <outline text="Mornings"><outline type="rss" text="News Daily" xmlUrl="https://publisher.example/news"/></outline>
            <outline type="rss" text="Tech Talk" xmlUrl="https://publisher.example/tech"/></body></opml>"""
        assertEquals(2, library.importOpml(xml))
        val saved = library.state.value
        val newsId = saved.shows.first { it.title == "News Daily" }.id
        assertEquals(mapOf(newsId to setOf("Mornings")), saved.showTags)
        val out = PodioTags.opml(saved)
        assertTrue(out.contains("<outline text=\"Mornings\" title=\"Mornings\">\n    <outline type=\"rss\" text=\"News Daily\""), out)
        assertEquals(mapOf("https://publisher.example/news" to setOf("Mornings")), PodioTags.foldersOf(out))
        assertEquals(2, PodioOpml.decode(out).size, "other apps still read every show")
    }

    @Test fun favouritesToggleNewestFirst() {
        var saved = PodioFavourites.toggle(PodioSaved(), entry("a"))
        saved = PodioFavourites.toggle(saved, entry("b"))
        assertEquals(listOf("b", "a"), saved.favouriteEpisodes.map { it.episode.id })
        assertTrue(PodioFavourites.isFavourite(saved, entry("a").id))
        assertEquals(listOf("b"), PodioFavourites.toggle(saved, entry("a")).favouriteEpisodes.map { it.episode.id })
    }

    @Test fun chosenQueueEntriesMoveToTheEdgeOfTheirGroup() {
        var state = PodioQueueState()
        listOf("a", "b", "c").forEach { state = PodioQueueOrder.add(state, entry(it)) }
        state = PodioQueueOrder.playNext(state, entry("x"))
        state = PodioQueueOrder.playNext(state, entry("y"))
        val chosen = setOf(entry("c").id, entry("y").id)
        assertEquals(listOf("y", "x", "c", "a", "b"), PodioQueueOrder.moveToEdge(state, chosen, top = true).entries.map { it.episode.id })
        assertEquals(listOf("x", "y", "b", "c", "a"), PodioQueueOrder.moveToEdge(state, setOf(entry("a").id), top = false).entries.map { it.episode.id })
        assertEquals(listOf("x", "a", "b"), PodioQueueOrder.removeAll(state, chosen).entries.map { it.episode.id })
    }
}
