package com.cosmotune.video.source

import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem

/** The channel tabs beyond Videos, Shorts, Live and Playlists: Podcasts, Courses, Releases and Posts. */
enum class ExtraChannelTab(val path: String) { PODCASTS("podcasts"), COURSES("courses"), RELEASES("releases"), POSTS("posts") }

/** One post of a channel's Posts tab: its words, when, its likes as the site writes them, its pictures and a video it shares. */
data class ChannelPost(
    val id: String,
    val text: String,
    val publishedText: String?,
    val likesText: String?,
    val imageUrls: List<String> = emptyList(),
    val video: VideoItem? = null,
)

/**
 * What a channel page reads besides the source's own tabs (the site's own browse endpoint): searching inside the channel, the tabs
 * it has of [ExtraChannelTab], one page of such a tab (podcasts, courses and releases are playlists), and its posts.
 */
interface ChannelExtras {
    suspend fun search(channelId: String, query: String, page: PageToken? = null): Page<VideoItem>

    /** The extra tabs the channel has, each with what it takes to ask for it. */
    suspend fun tabs(channelId: String): Map<ExtraChannelTab, String>

    suspend fun tab(channelId: String, params: String, page: PageToken? = null): Page<ListItem>

    suspend fun posts(channelId: String, params: String, page: PageToken? = null): Page<ChannelPost>
}
