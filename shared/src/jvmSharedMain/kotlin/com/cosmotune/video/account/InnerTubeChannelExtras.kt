package com.cosmotune.video.account

import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.ChannelExtras
import com.cosmotune.video.source.ChannelPost
import com.cosmotune.video.source.ExtraChannelTab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * [ChannelExtras] on the site's own `browse` endpoint (the WEB client; signed out unless Music holds a session). The channel's tab
 * list names the extra tabs by their address ("/@name/podcasts") and gives the params to ask for each; the search tab is asked with
 * its own params and the words. Lists are read with [YoutubeFeedParser]; posts with [parsePosts].
 */
class InnerTubeChannelExtras internal constructor(private val web: YoutubeWeb) : ChannelExtras {
    constructor() : this(InnertubeWeb)

    override suspend fun search(channelId: String, query: String, page: PageToken?): Page<VideoItem> {
        val response = if (page != null) browse { put("continuation", page.value) } else browse {
            put("browseId", channelId)
            put("params", SEARCH_PARAMS)
            put("query", query)
        }
        val feed = YoutubeFeedParser.parse(response)
        return Page(feed.videos, feed.continuation?.let(::PageToken))
    }

    override suspend fun tabs(channelId: String): Map<ExtraChannelTab, String> = tabsOf(browse { put("browseId", channelId) })

    override suspend fun tab(channelId: String, params: String, page: PageToken?): Page<ListItem> {
        val response = if (page != null) browse { put("continuation", page.value) } else browse {
            put("browseId", channelId)
            put("params", params)
        }
        val feed = YoutubeFeedParser.parse(response)
        val items = feed.playlists.map { ListItem.Playlist(it) } + feed.videos.map { ListItem.Video(it) }
        return Page(items, feed.continuation?.let(::PageToken))
    }

    override suspend fun posts(channelId: String, params: String, page: PageToken?): Page<ChannelPost> {
        val response = if (page != null) browse { put("continuation", page.value) } else browse {
            put("browseId", channelId)
            put("params", params)
        }
        return Page(parsePosts(response), YoutubeFeedParser.parse(response).continuation?.let(::PageToken))
    }

    private suspend fun browse(body: JsonObjectBuilder.() -> Unit): JsonObject {
        val response = web.post("browse", body)
        YoutubeFeedParser.errorOf(response)?.let { throw it }
        return response
    }

    companion object {
        /** The channel search tab's params ("search"). */
        const val SEARCH_PARAMS = "EgZzZWFyY2jyBgQKAloA"

        /** The extra tabs of a channel's browse answer, by the end of each tab's address. */
        internal fun tabsOf(response: JsonObject): Map<ExtraChannelTab, String> {
            val tabs = response.obj("contents").obj("twoColumnBrowseResultsRenderer").arr("tabs") ?: return emptyMap()
            val out = LinkedHashMap<ExtraChannelTab, String>()
            for (tab in tabs) {
                val renderer = tab.obj("tabRenderer") ?: continue
                val endpoint = renderer.obj("endpoint") ?: continue
                val url = endpoint.obj("commandMetadata").obj("webCommandMetadata").str("url")
                    ?: endpoint.obj("browseEndpoint").str("canonicalBaseUrl") ?: continue
                val params = endpoint.obj("browseEndpoint").str("params") ?: continue
                val last = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
                val kind = ExtraChannelTab.entries.firstOrNull { it.path == last || (it == ExtraChannelTab.POSTS && last == "community") } ?: continue
                out[kind] = params
            }
            return out
        }

        /** The posts of a Posts tab answer (or its continuation): the text, the time, the likes, pictures and a shared video. */
        internal fun parsePosts(response: JsonObject): List<ChannelPost> {
            val posts = ArrayList<ChannelPost>()
            fun visit(node: JsonElement?) {
                when (node) {
                    is JsonObject -> {
                        val post = node.obj("backstagePostRenderer") ?: node.obj("postRenderer")
                        if (post != null) {
                            postOf(post)?.let { posts += it }
                            return
                        }
                        node.values.forEach { visit(it) }
                    }
                    is JsonArray -> node.forEach { visit(it) }
                    else -> Unit
                }
            }
            visit(response["contents"])
            visit(response["onResponseReceivedEndpoints"])
            return posts.distinctBy { it.id }
        }

        private fun postOf(post: JsonObject): ChannelPost? {
            val id = post.str("postId") ?: return null
            val attachment = post.obj("backstageAttachment")
            val images = buildList {
                attachment.obj("backstageImageRenderer")?.obj("image")?.arr("thumbnails")?.let { YoutubeFeedParser.bestUrl(it)?.let(::add) }
                attachment.obj("postMultiImageRenderer")?.arr("images")?.forEach { image ->
                    image.obj("backstageImageRenderer")?.obj("image")?.arr("thumbnails")?.let { YoutubeFeedParser.bestUrl(it)?.let(::add) }
                }
            }
            val video = attachment.obj("videoRenderer")?.let { v -> videoOf(v) }
            val text = YoutubeFeedParser.text(post["contentText"]).orEmpty()
            if (text.isBlank() && images.isEmpty() && video == null) return null
            return ChannelPost(
                id = id,
                text = text,
                publishedText = YoutubeFeedParser.text(post["publishedTimeText"]),
                likesText = YoutubeFeedParser.text(post["voteCount"]),
                imageUrls = images,
                video = video,
            )
        }

        private fun videoOf(v: JsonObject): VideoItem? {
            val videoId = v.str("videoId") ?: return null
            return VideoItem(
                id = videoId,
                title = YoutubeFeedParser.text(v["title"]).orEmpty(),
                channelId = null,
                channelName = YoutubeFeedParser.text(v["ownerText"]),
                channelAvatarUrl = null,
                thumbnailUrl = YoutubeFeedParser.bestUrl(v.obj("thumbnail")?.arr("thumbnails")) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                durationSec = YoutubeFeedParser.parseDuration(YoutubeFeedParser.text(v["lengthText"])),
                viewCount = YoutubeFeedParser.parseCount(YoutubeFeedParser.text(v["viewCountText"])),
                uploadedAtMs = null,
                uploadedText = YoutubeFeedParser.text(v["publishedTimeText"]),
            )
        }
    }
}
