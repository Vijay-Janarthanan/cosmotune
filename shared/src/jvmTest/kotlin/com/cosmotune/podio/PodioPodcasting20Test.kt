package com.cosmotune.podio

import kotlin.test.*

class PodioPodcasting20Test {
    private val feed = """<?xml version="1.0"?>
        <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
        <channel><title>Show</title>
          <podcast:funding url="https://support.example/show">Support us</podcast:funding>
          <podcast:person href="https://hosts.example/ana" img="ana.jpg">Ana</podcast:person>
          <item><title>One</title><enclosure url="https://cdn.example/1.mp3" type="audio/mpeg"/>
            <itunes:season>2</itunes:season><podcast:episode>14</podcast:episode>
            <podcast:person role="guest">Ben</podcast:person><podcast:person role="guest">ben</podcast:person>
          </item>
          <item><title>Two</title><enclosure url="https://cdn.example/2.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""

    @Test fun peopleFundingAndNumbersAreRead() {
        val parsed = PodioCatalogue.parseFeed(feed, "https://feeds.example/show.xml")
        assertEquals(listOf(PodcastLink("https://support.example/show", "Support us")), parsed.show.funding)
        assertEquals(listOf(PodcastPerson("Ana", "host", "https://hosts.example/ana", "https://feeds.example/ana.jpg")), parsed.show.people)
        val first = parsed.episodes.first()
        assertEquals(2, first.season)
        assertEquals(14, first.number)
        assertEquals(listOf("Ben"), first.people.map { it.name }, "one entry a person and role")
        assertEquals("guest", first.people.single().role)
        assertNull(parsed.episodes.last().number)
    }
}
