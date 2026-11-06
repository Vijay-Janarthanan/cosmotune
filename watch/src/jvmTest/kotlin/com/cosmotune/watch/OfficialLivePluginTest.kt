package com.cosmotune.watch

import com.cosmotune.watch.LiveVideoSource.Companion.video
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugins.youtube.OfficialLivePlugin
import com.cosmotune.watch.plugins.youtube.PrasarBharatiPlugin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialLivePluginTest {
    private val now = 1_762_461_600_000L
    private val ddNews = PrasarBharatiPlugin.CHANNELS.first { it.slug == "DDNews" }
    private val ddSports = PrasarBharatiPlugin.CHANNELS.first { it.slug == PrasarBharatiPlugin.DD_SPORTS }

    @Test fun everyChannelIdIsAYouTubeChannelIdAndEverySlugIsUnique() {
        for (list in listOf(PrasarBharatiPlugin.CHANNELS)) {
            assertTrue(list.all { Regex("UC[A-Za-z0-9_-]{22}").matches(it.youTubeId) }, "a channel id, never a handle or a video")
            assertEquals(list.size, list.map { it.slug }.toSet().size)
            assertEquals(list.size, list.map { it.youTubeId }.toSet().size)
            assertTrue(list.all { it.slug.all(Char::isLetterOrDigit) })
        }
        assertTrue(PrasarBharatiPlugin.CHANNELS.any { it.slug == "DDTamil" && "tam" in it.languages })
    }

    @Test fun ddListsItsChannelsWithLogosAndLabelsAndPlaysWhatIsLive() = runBlocking<Unit> {
        val source = LiveVideoSource()
        source.live[ddNews.youTubeId] = listOf(video("ddnewsLive1", "🔴LIVE : DD News 24x7", ddNews.youTubeId, live = true))
        source.live[ddSports.youTubeId] = listOf(video("ddsportsUp1", "LIVE - India v Australia, 2nd T20I", ddSports.youTubeId, upcoming = true, atMs = now + 3_600_000))
        val plugin = PrasarBharatiPlugin({ source }, { now })
        val channels = plugin.channels()
        assertEquals(PrasarBharatiPlugin.CHANNELS.size, channels.size)
        val news = channels.first { it.name == "DD News" }
        assertEquals(WatchKey("c:dd:DDNews"), news.key)
        assertEquals("https://yt3.example/${ddNews.youTubeId}.jpg", news.logoUrl, "the logo is the channel's own avatar")
        assertEquals(listOf("in"), news.countries)
        assertEquals(emptyList(), news.labels)
        val sports = channels.first { it.name == "DD Sports" }
        assertEquals(listOf(OfficialLivePlugin.NOT_ALL_DAY), sports.labels)

        val stream = plugin.streams(PlayTarget.Live(news)).single()
        assertEquals("https://www.youtube.com/watch?v=ddnewsLive1", stream.url)
        assertEquals(StreamProtocol.YOUTUBE, stream.protocol)
        assertTrue(stream.isLive && stream.isPublic)
        assertEquals("DD News · DD News 24x7", stream.description)
        assertEquals(emptyList(), plugin.streams(PlayTarget.Live(sports)), "nothing live: no stream, never somebody else's")

        val guide = plugin.guide(listOf(news, sports), now - 6 * 3_600_000, now + 12 * 3_600_000)
        assertEquals(listOf("DD News 24x7", "India v Australia, 2nd T20I"), guide.map { it.title })
        assertTrue(guide[0].isOnAt(now))
        assertEquals(now + 3_600_000, guide[1].startMs)

        val item = PlayTarget.Item(WatchKey.item("dd", "ddsportsUp1"), "dd", "ddsportsUp1", "India v Australia")
        assertEquals("https://www.youtube.com/watch?v=ddsportsUp1", plugin.streams(item).single().url)
        assertEquals(emptyList(), plugin.streams(PlayTarget.Item(WatchKey.item("ia", "x"), "ia", "x", "x")))
    }

    @Test fun theGuideAsksYouTubeAtMostOncePerChannelInAFewMinutes() = runBlocking<Unit> {
        val source = LiveVideoSource()
        var t = now
        val plugin = PrasarBharatiPlugin({ source }, { t })
        val channels = plugin.channels()
        plugin.guide(channels, t, t + 3_600_000)
        plugin.guide(channels, t, t + 3_600_000)
        assertEquals(channels.size, source.tabAsks.get())
        t += 5 * 60_000
        plugin.guide(channels.take(1), t, t + 3_600_000)
        assertEquals(channels.size + 1, source.tabAsks.get())
    }

    @Test fun aLiveVideoFromAnotherChannelIsNeverPlayed() = runBlocking<Unit> {
        val source = LiveVideoSource()
        source.live[ddNews.youTubeId] = listOf(video("otherLive01", "Re-upload", "UCsomebodyElse000000000", live = true))
        val plugin = PrasarBharatiPlugin({ source }, { now })
        val news = plugin.channels().first { it.name == "DD News" }
        assertEquals(emptyList(), plugin.streams(PlayTarget.Live(news)))
    }

    @Test fun quietWithoutVideoMode() = runBlocking<Unit> {
        val plugin = PrasarBharatiPlugin({ null }, { now })
        assertEquals(emptyList(), plugin.channels())
        assertEquals("Needs Video mode", plugin.health())
    }

    @Test fun cleanTitleDropsTheLiveMarker() {
        assertEquals("Chennai Rains | Sun News", OfficialLivePlugin.cleanTitle("🔴LIVE : Chennai Rains | Sun News"))
        assertEquals("Today Headlines", OfficialLivePlugin.cleanTitle("LIVE | Today Headlines"))
        assertEquals("Live from Sriharikota", OfficialLivePlugin.cleanTitle("Live from Sriharikota"), "words that only start with Live stay")
        assertEquals("Trisha & Vijay", OfficialLivePlugin.cleanTitle("Trisha &amp; Vijay"))
    }
}
