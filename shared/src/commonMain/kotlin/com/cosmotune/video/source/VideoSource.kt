package com.cosmotune.video.source

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

/**
 * Where Video mode reads videos from (spec 4.2): kiosks, a kiosk page, suggestions, search, video details, fresh
 * streams, channel header and tabs, playlist pages, comments and replies, and the region. Paging by an opaque
 * [PageToken]; every failure is a [VideoSourceError]. The real one is the NewPipe adapter (plan task Y-4); the screens and
 * the controller are built against [FakeVideoSource].
 */
interface VideoSource {
    /** The charts the source offers for the region (spec 5.2: Music, Gaming, Movies and shows, Podcasts, Live). */
    suspend fun kiosks(): List<Kiosk>

    /** One page of kiosk [id] (spec 5.2: the selected kiosk as feed cards, loading more at the end). */
    suspend fun kiosk(id: String, page: PageToken? = null): Page<VideoItem>

    /** Search suggestions for what is typed (spec 5.3). */
    suspend fun suggestions(query: String): List<String>

    /** One page of results for [query] with [filter] (spec 5.3; [SearchFilter.SHORTS] keeps the Shorts of an `ALL` search). */
    suspend fun search(query: String, filter: SearchFilter, page: PageToken? = null): Page<ListItem>

    /** The watch page's data (spec 5.4): metadata, chapters, related, subtitles, storyboard and streams. */
    suspend fun details(videoId: String): VideoDetails

    /**
     * The related videos of [videoId] (the watch page's "Up next", spec 5.4), for callers that need only the list. A source that has
     * a second way to ask for them when [details] holds none answers with that one; it throws a [VideoSourceError] when it cannot
     * answer at all (an empty list means the video has no suggestions). Plain sources answer [details]' own list.
     */
    suspend fun related(videoId: String): List<ListItem> = details(videoId).related

    /** A fresh stream set, never cached (spec 6.1: the refresh after a 403). */
    suspend fun streams(videoId: String): StreamSet

    /** A channel's header, by id or by any channel address (`/channel/UC...`, `/@handle`, `/user/name`; spec 5.5, 8.2). */
    suspend fun channel(channelIdOrUrl: String): ChannelPage

    /** One page of a channel tab (spec 5.5). */
    suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken? = null): Page<ListItem>

    /** A playlist page (spec 5.6); with [page] not null only [PlaylistPage.first] is meaningful (the next page of videos). */
    suspend fun playlist(playlistId: String, page: PageToken? = null): PlaylistPage

    /** One page of a video's comments (spec 5.9). */
    suspend fun comments(videoId: String, sort: CommentSort = CommentSort.TOP, page: PageToken? = null): CommentPage

    /** The replies a comment's [com.cosmotune.video.model.Comment.replies] token opens, or their next page (spec 5.9). */
    suspend fun replies(token: PageToken): CommentPage

    /** The country and content language the source answers for (spec 5.11: region for Home, content language). */
    fun setRegion(country: String, language: String)
}
