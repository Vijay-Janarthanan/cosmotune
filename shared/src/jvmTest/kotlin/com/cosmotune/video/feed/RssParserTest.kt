package com.cosmotune.video.feed

import com.cosmotune.video.feed.rss.RssParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tolerant scan of a channel's Atom feed (plan task Y-9, step 1; spec 2.7), on the recorded feeds of 2026-10-16. */
class RssParserTest {

    @Test
    fun theMrBeastChannelFeedHasFifteenEntriesAndNineShortsLinks() {
        val entries = RssParser.parse(FeedFixtures.mrBeastChannelFeed)
        assertEquals(15, entries.size)
        assertEquals(9, entries.count { it.isShortLink })
        assertTrue(entries.all { it.channelId == FeedFixtures.MRBEAST_CHANNEL_ID })
        assertEquals(15, entries.map { it.videoId }.toSet().size)
    }

    @Test
    fun theKurzgesagtChannelFeedHasFifteenEntriesAndElevenShortsLinks() {
        val entries = RssParser.parse(FeedFixtures.kurzgesagtChannelFeed)
        assertEquals(15, entries.size)
        assertEquals(11, entries.count { it.isShortLink })
        assertTrue(entries.all { it.channelId == FeedFixtures.KURZGESAGT_CHANNEL_ID })
    }

    @Test
    fun theLongFormPlaylistFeedHasFifteenEntriesAndNoShortsLinks() {
        val entries = RssParser.parse(FeedFixtures.mrBeastLongFormFeed)
        assertEquals(15, entries.size)
        assertEquals(0, entries.count { it.isShortLink })
        assertTrue(entries.all { it.link!!.contains("/watch?v=") })
    }

    @Test
    fun theFirstEntryOfTheMrBeastFeedReadsEveryField() {
        val first = RssParser.parse(FeedFixtures.mrBeastChannelFeed).first()
        assertEquals("aNI2jv7lBPQ", first.videoId)
        assertEquals("UCX6OQ3DkcsbYNE6H8uQQuVA", first.channelId)
        assertEquals("Giving \$10,000 To A Random Subscriber", first.title)
        assertEquals("https://www.youtube.com/shorts/aNI2jv7lBPQ", first.link)
        assertTrue(first.isShortLink)
        // 2026-10-09T16:00:01+00:00 and 2026-10-13T09:10:32+00:00.
        assertEquals(1_791_561_601_000L, first.publishedMs)
        assertEquals(1_791_882_632_000L, first.updatedMs)
        assertEquals("https://i2.ytimg.com/vi/aNI2jv7lBPQ/hqdefault.jpg", first.thumbnailUrl)
        assertEquals(30_266_101L, first.views)
    }

    @Test
    fun theSecondEntryIsALongVideoLinkedToTheWatchPage() {
        val second = RssParser.parse(FeedFixtures.mrBeastChannelFeed)[1]
        assertEquals("plN7JMbadRg", second.videoId)
        assertEquals("https://www.youtube.com/watch?v=plN7JMbadRg", second.link)
        assertFalse(second.isShortLink)
        assertEquals(264_174_125L, second.views)
        assertEquals("Eat Everything In A Grocery Store, Win \$1,000,000", second.title)
    }

    @Test
    fun theTitlesKeepTheirTypographicApostrophes() {
        val titles = RssParser.parse(FeedFixtures.mrBeastChannelFeed).map { it.title }
        assertTrue("What’s Inside My Briefcase?" in titles, titles.toString())
        assertTrue("Don't Pop the Balloon" in titles)
    }

    @Test
    fun theNotFoundPageGivesAnEmptyListAndDoesNotThrow() {
        assertEquals(emptyList(), RssParser.parse(FeedFixtures.notFoundBody))
        assertFalse(RssParser.looksLikeFeed(FeedFixtures.notFoundBody))
        assertTrue(RssParser.looksLikeFeed(FeedFixtures.mrBeastChannelFeed))
    }

    @Test
    fun emptyAndBrokenInputGivesAnEmptyListAndDoesNotThrow() {
        assertEquals(emptyList(), RssParser.parse(""))
        assertEquals(emptyList(), RssParser.parse("   \n"))
        assertEquals(emptyList(), RssParser.parse("<feed></feed>"))
        assertEquals(emptyList(), RssParser.parse("<feed><entry><yt:videoId>abc"))
        assertEquals(emptyList(), RssParser.parse("<<<>>> & <entry"))
        assertEquals(emptyList(), RssParser.parse("<entry>"))
    }

    @Test
    fun aTruncatedFeedKeepsTheEntriesThatAreComplete() {
        val xml = FeedFixtures.mrBeastChannelFeed
        val cut = xml.indexOf("</entry>", xml.indexOf("</entry>") + 1) + "</entry>".length + 40
        assertEquals(2, RssParser.parse(xml.substring(0, cut)).size)
    }

    @Test
    fun entitiesAreDecodedInTitlesAndLinks() {
        val xml = entry(
            videoId = "abc123",
            title = "Fish &amp; Chips &lt;b&gt; &quot;hot&quot; &apos;n&apos; &#39;cold&#39; &#x2019; &#8364; &#x1F600; &amp;amp; &unknown; &#xZZ; &#99999999;",
            link = "https://www.youtube.com/watch?v=abc123&amp;t=5s",
        )
        val entry = RssParser.parse(xml).single()
        assertEquals("Fish & Chips <b> \"hot\" 'n' 'cold' ’ € 😀 &amp; &unknown; &#xZZ; &#99999999;", entry.title)
        assertEquals("https://www.youtube.com/watch?v=abc123&t=5s", entry.link)
    }

    @Test
    fun aCdataTitleIsTakenAsItIs() {
        val xml = entry(videoId = "abc123", title = "<![CDATA[Fish &amp; <Chips>]]>")
        assertEquals("Fish &amp; <Chips>", RssParser.parse(xml).single().title)
    }

    @Test
    fun aTitleOnSeveralLinesIsOneLine() {
        val xml = entry(videoId = "abc123", title = "First\r\n  second\nthird")
        assertEquals("First   second third", RssParser.parse(xml).single().title)
    }

    @Test
    fun datesAreIsoEightySixOhOneToEpochMilliseconds() {
        fun published(text: String): Long? = RssParser.parse(entry(videoId = "abc123", published = text)).single().publishedMs
        assertEquals(0L, published("1970-01-01T00:00:00+00:00"))
        assertEquals(0L, published("1970-01-01T00:00:00Z"))
        assertEquals(1_791_561_601_000L, published("2026-10-09T16:00:01+00:00"))
        // An offset is subtracted: 21:30 at +05:30 is 16:00 UTC.
        assertEquals(1_791_561_600_000L, published("2026-10-09T21:30:00+05:30"))
        assertEquals(1_791_561_600_000L, published("2026-10-09T21:30:00+0530"))
        assertEquals(1_791_561_600_000L, published("2026-10-09T11:00:00-05:00"))
        // Fractions are read: .5 s is 500 ms, and a long fraction is cut at the millisecond.
        assertEquals(1_791_561_600_500L, published("2026-10-09T16:00:00.5Z"))
        assertEquals(1_791_561_600_123L, published("2026-10-09T16:00:00.123456Z"))
        // No zone is read as UTC; a date alone is its midnight.
        assertEquals(1_791_561_600_000L, published("2026-10-09T16:00:00"))
        assertEquals(1_791_504_000_000L, published("2026-10-09"))
        // Leap days.
        assertEquals(951_782_400_000L, published("2000-02-29T00:00:00Z"))
        assertEquals(1_709_164_800_000L, published("2024-02-29T00:00:00Z"))
    }

    @Test
    fun aBadOrMissingDateIsNull() {
        fun published(text: String?): Long? = RssParser.parse(entry(videoId = "abc123", published = text)).single().publishedMs
        assertNull(published(null))
        assertNull(published("yesterday"))
        assertNull(published("2026-13-01T00:00:00Z"))
        assertNull(published("2026-02-30T00:00:00Z"))
        assertNull(published("2026-10-09T25:00:00Z"))
        assertNull(published(""))
    }

    @Test
    fun anEntryWithoutAVideoIdTakesItFromTheIdElementOrIsSkipped() {
        val fromId = "<entry><id>yt:video:fromIdElem1</id><title>T</title></entry>"
        assertEquals("fromIdElem1", RssParser.parse(fromId).single().videoId)
        assertEquals(emptyList(), RssParser.parse("<entry><title>No id at all</title></entry>"))
        assertEquals(emptyList(), RssParser.parse("<entry><yt:videoId>  </yt:videoId><title>T</title></entry>"))
    }

    @Test
    fun missingOptionalPartsAreNullAndTheEntryStays() {
        val entry = RssParser.parse("<entry><yt:videoId>abc123</yt:videoId></entry>").single()
        assertEquals("abc123", entry.videoId)
        assertEquals("", entry.title)
        assertNull(entry.channelId)
        assertNull(entry.link)
        assertNull(entry.publishedMs)
        assertNull(entry.updatedMs)
        assertNull(entry.thumbnailUrl)
        assertNull(entry.views)
        assertFalse(entry.isShortLink)
    }

    @Test
    fun theAlternateLinkIsTheOneThatCountsWhateverTheOrderOfItsAttributes() {
        val xml = "<entry><yt:videoId>abc123</yt:videoId>" +
            "<link rel=\"self\" href=\"https://example.invalid/self\"/>" +
            "<link href='https://www.youtube.com/shorts/abc123' rel='alternate' />" +
            "</entry>"
        val entry = RssParser.parse(xml).single()
        assertEquals("https://www.youtube.com/shorts/abc123", entry.link)
        assertTrue(entry.isShortLink)
    }

    @Test
    fun theMediaTitleIsNotTakenForTheTitle() {
        val xml = "<entry><yt:videoId>abc123</yt:videoId><media:group><media:title>Media title</media:title></media:group><title>Real title</title></entry>"
        assertEquals("Real title", RssParser.parse(xml).single().title)
        val mediaOnly = "<entry><yt:videoId>abc123</yt:videoId><media:group><media:title>Media title</media:title></media:group></entry>"
        assertEquals("", RssParser.parse(mediaOnly).single().title)
    }

    @Test
    fun viewsThatAreNotANumberAreNull() {
        val xml = "<entry><yt:videoId>abc123</yt:videoId><media:statistics views=\"many\"/></entry>"
        assertNull(RssParser.parse(xml).single().views)
        val zero = "<entry><yt:videoId>abc123</yt:videoId><media:statistics views=\"0\"/></entry>"
        assertEquals(0L, RssParser.parse(zero).single().views)
    }

    @Test
    fun entriesComeInTheOrderOfTheFeed() {
        val ids = RssParser.parse(FeedFixtures.mrBeastChannelFeed).map { it.videoId }
        assertEquals(listOf("aNI2jv7lBPQ", "plN7JMbadRg", "CEJXqm2eiJ0", "v9QtM6qnG50"), ids.take(4))
    }

    /** One entry as the feed writes it, with the parts a test sets. */
    private fun entry(
        videoId: String,
        title: String = "Title",
        link: String = "https://www.youtube.com/watch?v=$videoId",
        published: String? = "2026-10-09T16:00:01+00:00",
    ): String = buildString {
        append("<feed><entry>")
        append("<id>yt:video:$videoId</id><yt:videoId>$videoId</yt:videoId><yt:channelId>UCX6OQ3DkcsbYNE6H8uQQuVA</yt:channelId>")
        append("<title>$title</title><link rel=\"alternate\" href=\"$link\"/>")
        if (published != null) append("<published>$published</published>")
        append("<updated>2026-10-13T09:10:32+00:00</updated>")
        append("</entry></feed>")
    }
}
