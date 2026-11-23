package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodioSmartListWordsTest {
    @Test fun wordsKeepAndLeaveOutEpisodes() {
        assertTrue(PodioSmartLists.matchesWords("interview", "An Interview with Ann"))
        assertFalse(PodioSmartLists.matchesWords("interview -trailer", "Interview trailer"))
        assertTrue(PodioSmartLists.matchesWords("\"deep dive\"", "A deep dive into radio"))
        assertFalse(PodioSmartLists.matchesWords("\"deep dive\"", "Dive deep"))
        assertTrue(PodioSmartLists.matchesWords("cafe", "Le Café du matin"), "accents aside")
        assertTrue(PodioSmartLists.matchesWords("  ", "anything"))
        assertEquals(listOf("deep dive" to true, "news" to false), PodioSmartLists.terms("\"deep dive\" -news"))
    }

    @Test fun aListWithWordsKeepsOnlyThoseEpisodes() {
        val show = PodcastShow("s", "Show", "Host", "https://feeds.example/s.xml")
        val episodes = listOf("Weekly news", "Interview: Bo", "Trailer").mapIndexed { i, title -> PodcastEpisode("$i", title, "Show", "https://cdn.example/$i.mp3") to show }
        val kept = PodioSmartLists.match(PodioSmartList("l", "Talk", unplayed = false, words = "-trailer -news"), episodes, PodioSaved(), emptySet())
        assertEquals(listOf("Interview: Bo"), kept.map { it.first.title })
        assertEquals("“-trailer -news”", PodioSmartLists.describe(PodioSmartList("l", "Talk", unplayed = false, words = "-trailer -news")))
    }
}
