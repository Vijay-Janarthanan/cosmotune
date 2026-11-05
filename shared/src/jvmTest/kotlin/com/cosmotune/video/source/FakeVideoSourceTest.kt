package com.cosmotune.video.source

import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.SearchFilter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fake source the screens and the controller are built against (plan task Y-2, step 3): paging and the injected failure. */
class FakeVideoSourceTest {

    @Test
    fun itHasTwelveVideosWithTitlesAndTwoChannels() {
        val ids = FakeVideoSource.videos.map { it.id }
        assertEquals((1..12).map { "fakevideo%02d".format(it) }, ids)
        assertTrue(FakeVideoSource.videos.all { it.title.isNotBlank() && !it.isShort })
        assertEquals(listOf("UCfakechannel0000000001", "UCfakechannel0000000002"), FakeVideoSource.channels.map { it.id })
        assertEquals(setOf(FakeVideoSource.CHANNEL_1, FakeVideoSource.CHANNEL_2), FakeVideoSource.videos.mapNotNull { it.channelId }.toSet())
    }

    @Test
    fun thePlaylistOfThirtyPagesTenAtATime() = runBlocking {
        val source = FakeVideoSource()
        val page1 = source.playlist(FakeVideoSource.PLAYLIST_ID)
        assertEquals(30L, page1.playlist.videoCount)
        assertEquals(10, page1.first.items.size)
        val token1 = assertNotNull(page1.first.next)
        val page2 = source.playlist(FakeVideoSource.PLAYLIST_ID, token1)
        assertEquals(10, page2.first.items.size)
        val page3 = source.playlist(FakeVideoSource.PLAYLIST_ID, assertNotNull(page2.first.next))
        assertEquals(10, page3.first.items.size)
        assertNull(page3.first.next)
        val all = (page1.first.items + page2.first.items + page3.first.items).map { it.id }
        assertEquals(30, all.toSet().size)
        assertEquals(FakeVideoSource.playlistItems.map { it.id }, all)
    }

    @Test
    fun failNextThrowsOnceAndThenTheSourceAnswersAgain() = runBlocking {
        val source = FakeVideoSource()
        source.failNext(VideoSourceError.BotCheck("confirm you are not a bot"))
        assertFailsWith<VideoSourceError.BotCheck> { source.details("fakevideo01") }
        assertEquals("fakevideo01", source.details("fakevideo01").item.id)
        source.failNext(VideoSourceError.Network("offline", null))
        assertFailsWith<VideoSourceError.Network> { source.kiosks() }
        assertEquals(5, source.kiosks().size)
    }

    @Test
    fun theKiosksAreTheFiveOfTheSpecAndAKioskPages() = runBlocking {
        val source = FakeVideoSource()
        assertEquals(listOf("music", "gaming", "movies", "podcasts", "live"), source.kiosks().map { it.id })
        val first = source.kiosk("music")
        assertTrue(first.items.isNotEmpty())
        val second = source.kiosk("music", assertNotNull(first.next))
        assertNull(second.next)
        assertEquals(12, (first.items + second.items).map { it.id }.toSet().size)
    }

    @Test
    fun searchKeepsWhatEachFilterAsksFor() = runBlocking {
        val source = FakeVideoSource()
        val shorts = source.search("anything", SearchFilter.SHORTS)
        assertTrue(shorts.items.isNotEmpty())
        assertTrue(shorts.items.all { it is ListItem.Video && it.item.isShort })
        val videos = source.search("anything", SearchFilter.VIDEOS)
        assertTrue(videos.items.all { it is ListItem.Video && !it.item.isShort })
        assertTrue(source.search("anything", SearchFilter.CHANNELS).items.all { it is ListItem.Channel })
        assertTrue(source.search("anything", SearchFilter.PLAYLISTS).items.all { it is ListItem.Playlist })
        val all = source.search("anything", SearchFilter.ALL)
        assertTrue(all.items.any { it is ListItem.Channel } && all.items.any { it is ListItem.Video })
        assertTrue(source.search(FakeVideoSource.NO_RESULTS_QUERY, SearchFilter.ALL).items.isEmpty())
        assertTrue(source.suggestions("cats").all { it.startsWith("cats") })
    }

    @Test
    fun aChannelHasItsTabsAndItsShortsTabHoldsOnlyShorts() = runBlocking {
        val source = FakeVideoSource()
        val page = source.channel(FakeVideoSource.CHANNEL_1)
        assertEquals(FakeVideoSource.CHANNEL_1, page.channel.id)
        assertEquals(listOf(ChannelTab.VIDEOS, ChannelTab.SHORTS, ChannelTab.LIVE, ChannelTab.PLAYLISTS), page.tabs)
        val shorts = source.channelTab(FakeVideoSource.CHANNEL_1, ChannelTab.SHORTS)
        assertTrue(shorts.items.isNotEmpty())
        assertTrue(shorts.items.all { it is ListItem.Video && it.item.isShort && it.item.channelId == FakeVideoSource.CHANNEL_1 })
        val videos = source.channelTab(FakeVideoSource.CHANNEL_2, ChannelTab.VIDEOS)
        assertTrue(videos.items.all { it is ListItem.Video && it.item.channelId == FakeVideoSource.CHANNEL_2 })
    }

    @Test
    fun commentsPageAndHaveReplies() = runBlocking {
        val source = FakeVideoSource()
        val top = source.comments("fakevideo01")
        assertTrue(top.items.first().pinned)
        assertTrue(top.sortable && !top.disabled)
        val more = source.comments("fakevideo01", CommentSort.TOP, assertNotNull(top.next))
        assertTrue(more.items.isNotEmpty())
        val withReplies = top.items.first { it.replyCount > 0 }
        val replies = source.replies(assertNotNull(withReplies.replies))
        assertEquals(withReplies.replyCount, replies.items.size)
        assertTrue(source.comments(FakeVideoSource.COMMENTS_OFF_VIDEO).disabled)
    }

    @Test
    fun detailsCarryStreamsAndAnUnknownIdIsNotAvailable() = runBlocking {
        val source = FakeVideoSource()
        val details = source.details("fakevideo02")
        assertTrue(details.streams.videoOnly.isNotEmpty() && details.streams.audio.isNotEmpty())
        assertTrue(details.related.isNotEmpty())
        assertEquals(3, details.subtitles.size)
        assertEquals("fakeshort01", source.details("fakeshort01").item.id)
        assertFailsWith<VideoSourceError.NotAvailable> { source.details("nosuchvideo") }
        assertTrue(source.streams("fakevideo02").videoOnly.isNotEmpty())
        assertTrue(source.calls.contains("streams(fakevideo02)"))
    }
}
