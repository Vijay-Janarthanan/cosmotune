package com.cosmotune.video.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channel_search_go
import com.cosmotune.video.resources.video_channel_search_hint
import com.cosmotune.video.source.ChannelExtras
import com.cosmotune.video.source.ChannelPost
import com.cosmotune.video.source.ExtraChannelTab
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.ui.blocks.VideoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The channel page's extra sections: which of Podcasts, Courses, Releases and Posts the channel has (asked once), their lists, and
 * the search inside the channel (a new list for each search).
 */
@Stable
internal class ChannelExtrasModel(private val extras: ChannelExtras, private val channelId: String, private val scope: CoroutineScope) {
    var tabs: Map<ExtraChannelTab, String> by mutableStateOf(emptyMap())
        private set
    private var asked = false
    private val lists = HashMap<ExtraChannelTab, PagedItems<ListItem>>()
    private var postList: PagedItems<ChannelPost>? = null

    var query by mutableStateOf("")
    var results: PagedItems<VideoItem>? by mutableStateOf(null)
        private set

    fun start() {
        if (asked) return
        asked = true
        scope.launch {
            tabs = try {
                extras.tabs(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyMap()
            }
        }
    }

    fun list(tab: ExtraChannelTab): PagedItems<ListItem>? {
        val params = tabs[tab] ?: return null
        return lists.getOrPut(tab) { PagedItems(scope) { token -> extras.tab(channelId, params, token) }.also { it.start() } }
    }

    fun posts(): PagedItems<ChannelPost>? {
        val params = tabs[ExtraChannelTab.POSTS] ?: return null
        return postList ?: PagedItems(scope) { token -> extras.posts(channelId, params, token) }.also { postList = it; it.start() }
    }

    fun search() {
        val words = query.trim()
        if (words.isEmpty()) return
        results = PagedItems(scope) { token: com.cosmotune.video.model.PageToken? -> extras.search(channelId, words, token) }.also { it.start() }
    }

    companion object {
        fun sectionOf(tab: ExtraChannelTab): ChannelSection = when (tab) {
            ExtraChannelTab.PODCASTS -> ChannelSection.PODCASTS
            ExtraChannelTab.COURSES -> ChannelSection.COURSES
            ExtraChannelTab.RELEASES -> ChannelSection.RELEASES
            ExtraChannelTab.POSTS -> ChannelSection.POSTS
        }

        fun tabOf(section: ChannelSection): ExtraChannelTab? = ExtraChannelTab.entries.firstOrNull { sectionOf(it) == section }

        /** The page's sections with the extra tabs the channel has put before About, and Search at the end. */
        fun withExtras(sections: List<ChannelSection>, tabs: Set<ExtraChannelTab>): List<ChannelSection> {
            val extra = ExtraChannelTab.entries.filter { it in tabs }.map(::sectionOf)
            val about = sections.indexOf(ChannelSection.ABOUT).takeIf { it >= 0 } ?: sections.size
            return sections.take(about) + extra + sections.drop(about) + ChannelSection.SEARCH
        }
    }
}

/** The search inside the channel: the field and its button, then the videos found. */
internal fun PageItems.channelSearchItems(extras: ChannelExtrasModel, line: PageLine, onPlay: (VideoItem, List<VideoItem>) -> Unit, onMore: (VideoItem, List<VideoItem>) -> Unit) {
    full("search-field", "search") {
        val fonts = orbitFonts()
        val look = orbitLook()
        Row(line.fit(Modifier.padding(bottom = 12.dp)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitInputField(
                value = extras.query,
                onValueChange = { extras.query = it },
                placeholder = stringResource(Res.string.video_channel_search_hint),
                fonts = fonts,
                look = look,
                modifier = Modifier.weight(1f),
            )
            OrbitActionPill(stringResource(Res.string.video_channel_search_go), extras::search, fonts, look, primary = true, enabled = extras.query.isNotBlank())
        }
    }
    val results = extras.results ?: return
    val videos = results.items
    rows(videos, key = { index, item -> "s:$index:${item.id}" }, contentType = "video") { item ->
        VideoRow(item = item, onClick = { onPlay(item, videos) }, onMore = { onMore(item, videos) })
    }
    pagedStatus("channel-search", results, rowStyle = true)
}

/** Podcasts, Courses and Releases: the channel's playlists of that kind, as the Playlists tab draws them. */
internal fun PageItems.extraListItems(paged: PagedItems<ListItem>, key: String, line: PageLine, wide: Boolean, onOpenPlaylist: (String) -> Unit) {
    val playlists = paged.items.mapNotNull { (it as? ListItem.Playlist)?.item }.distinctBy { it.id }
    rows(playlists, key = { _, playlist -> "$key:${playlist.id}" }, contentType = "playlist") { playlist ->
        com.cosmotune.video.ui.blocks.PlaylistRow(item = playlist, onClick = { onOpenPlaylist(playlist.id) }, modifier = line.fit(Modifier), desktop = wide)
    }
    pagedStatus(key, paged, rowStyle = true)
}

/** The Posts tab: each post's time, words, pictures (a row that scrolls sideways) and the video it shares. */
internal fun PageItems.postItems(paged: PagedItems<ChannelPost>, line: PageLine, onPlay: (VideoItem) -> Unit, onMore: (VideoItem) -> Unit) {
    rows(paged.items, key = { _, post -> "post:${post.id}" }, contentType = "post") { post -> PostCard(post, line, onPlay, onMore) }
    pagedStatus("posts", paged, rowStyle = true)
}

@Composable
private fun PostCard(post: ChannelPost, line: PageLine, onPlay: (VideoItem) -> Unit, onMore: (VideoItem) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(
        line.fit(Modifier.padding(vertical = 6.dp))
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(look.row)
            .padding(16.dp),
    ) {
        val meta = listOfNotNull(post.publishedText, post.likesText?.let { "♥ $it" }).joinToString(" · ")
        if (meta.isNotEmpty()) {
            OrbitLabel(meta, fonts, look.dim)
            Spacer(Modifier.height(8.dp))
        }
        if (post.text.isNotBlank()) Text(post.text, style = fonts.body, color = look.ink)
        if (post.imageUrls.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (url in post.imageUrls) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
        }
        post.video?.let { video ->
            Spacer(Modifier.height(12.dp))
            VideoRow(item = video, onClick = { onPlay(video) }, onMore = { onMore(video) })
        }
    }
}
