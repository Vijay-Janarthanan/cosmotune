package com.cosmotune.podio

import kotlin.test.*

class PodcastExtrasTest {
    @Test fun speakerCuesRetainTimesUrlsAndFurtherColons() {
        val text = """1
            |00:00:01,000 --> 00:00:06,000
            |Alice: Meet at 10:30: https://example.org/path
            |
            |2
            |00:00:07,000 --> 00:00:08,000
            |Alice:
        """.trimMargin()
        val cue = PodcastExtras.transcript(text).single()
        assertEquals("Alice", cue.speaker)
        assertEquals("Meet at 10:30: https://example.org/path", cue.text)
        assertEquals(1000L, cue.startMs)
    }
    @Test fun chapterOrderAndUnsafeImagesAreHandledWithoutChangingAudio() {
        val chapters = PodcastExtras.chapters("""{"chapters":[{"startTime":12,"title":"Later","img":"file:///private"},{"startTime":0,"title":"Opening"},{"startTime":-1,"title":"Invalid"}]}""")
        assertEquals(listOf("Opening", "Later"), chapters.map { it.title })
        assertNull(chapters.last().image)
    }
    @Test fun feedAdvertisesExtrasWithoutFetchingThem() {
        val feed = PodioCatalogue.parseFeed("""<rss xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>A show</title><item><guid>1</guid><title>An episode</title><enclosure url="https://example.com/audio.mp3"/><podcast:chapters url="/chapters.json" type="application/json+chapters"/><podcast:transcript url="/words.srt" type="application/x-subrip" language="en"/></item></channel></rss>""", "https://example.com/feed")
        assertEquals("https://example.com/chapters.json", feed.episodes.single().chaptersUrl)
        assertEquals("en", feed.episodes.single().transcripts.single().language)
    }
}
