package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals

class PodioDirectoriesTest {
    @Test fun gpodderSearchGivesShowsWithFeeds() {
        val shows = PodioCatalogue.gpodderShows("""[
            {"url": "http://feeds.example/history", "title": "Hardcore History", "author": "Dan", "logo_url": "https://img.example/h.jpg", "website": "https://example.com"},
            {"url": "not a url", "title": "Broken"},
            {"url": "https://feeds.example/untitled", "title": " "}]""")
        assertEquals(listOf("Hardcore History"), shows.map { it.title })
        assertEquals("http://feeds.example/history", shows.single().feed)
        assertEquals("gPodder", shows.single().source)
        assertEquals("https://img.example/h.jpg", shows.single().artwork)
    }

    @Test fun podverseSearchSkipsShowsThatNeedASignIn() {
        val shows = PodioCatalogue.podverseShows("""[[
            {"title": "Open Show", "imageUrl": "https://img.example/o.jpg", "linkUrl": "https://o.example", "feedUrls": [{"url": "https://feeds.example/open"}], "authors": [{"name": "Ann"}]},
            {"title": "Private", "credentialsRequired": true, "feedUrls": [{"url": "https://feeds.example/private"}]},
            {"title": "No feed", "feedUrls": []}], 3]""")
        assertEquals(listOf("Open Show"), shows.map { it.title })
        assertEquals("Ann", shows.single().author)
        assertEquals("Podverse", shows.single().source)
    }
}
