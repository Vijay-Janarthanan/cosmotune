package com.cosmotune.watch

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistPage
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A Video source that answers only channel pages and Live tabs, from [live] (channel id to its Live tab), counting the asks. */
class LiveVideoSource(val live: MutableMap<String, List<VideoItem>> = ConcurrentHashMap()) : VideoSource {
    val tabAsks = AtomicInteger()
    val tabAsksBy = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken?): Page<ListItem> {
        require(tab == ChannelTab.LIVE) { "only the Live tab is asked" }
        tabAsks.incrementAndGet(); tabAsksBy.getOrPut(channelId) { AtomicInteger() }.incrementAndGet()
        return Page(live[channelId].orEmpty().map { ListItem.Video(it) }, null)
    }

    override suspend fun channel(channelIdOrUrl: String): ChannelPage =
        ChannelPage(ChannelItem(channelIdOrUrl, "Channel", "https://yt3.example/$channelIdOrUrl.jpg", null, null, null, verified = true), null, listOf(ChannelTab.LIVE), null, emptyList())

    override suspend fun kiosks(): List<Kiosk> = emptyList()
    override suspend fun kiosk(id: String, page: PageToken?): Page<VideoItem> = Page(emptyList(), null)
    override suspend fun suggestions(query: String): List<String> = emptyList()
    override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Page<ListItem> = Page(emptyList(), null)
    override suspend fun details(videoId: String): VideoDetails = throw UnsupportedOperationException()
    override suspend fun streams(videoId: String): StreamSet = throw UnsupportedOperationException()
    override suspend fun playlist(playlistId: String, page: PageToken?): PlaylistPage = throw UnsupportedOperationException()
    override suspend fun comments(videoId: String, sort: CommentSort, page: PageToken?): CommentPage = throw UnsupportedOperationException()
    override suspend fun replies(token: PageToken): CommentPage = throw UnsupportedOperationException()
    override fun setRegion(country: String, language: String) {}

    companion object {
        fun video(id: String, title: String, channelId: String, live: Boolean = false, upcoming: Boolean = false, atMs: Long? = null) =
            VideoItem(id, title, channelId, "Channel", null, "https://i.example/$id.jpg", null, null, atMs, null, isLive = live, isUpcoming = upcoming)
    }
}
