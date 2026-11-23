package com.cosmotune.video.ui.channel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_elsewhere_follow
import com.cosmotune.video.resources.video_elsewhere_following
import com.cosmotune.video.resources.video_elsewhere_title
import com.cosmotune.video.segments.TtlCache
import com.cosmotune.video.source.peertube.PeerChannelRef
import com.cosmotune.video.ui.actions.ActionToastKind
import com.cosmotune.video.ui.actions.VideoActionsState
import com.cosmotune.video.ui.blocks.VideoShelf
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.subscriptions.OtherSites
import org.jetbrains.compose.resources.stringResource

/** What the index said for a creator's name (kept an hour: one look-up for each channel page). */
private object Elsewhere {
    val found = TtlCache<String, List<PeerChannelRef>>(60 * 60_000L, 256, System::currentTimeMillis)
}

/**
 * The same creator on other sites, under a channel's card: each PeerTube channel with the same name (from the index of every
 * PeerTube site), its site, Follow (it then joins the Subscriptions feed) and its newest videos, which play from their site.
 * Nothing shows when no such channel exists.
 */
@Composable
internal fun ChannelElsewhere(name: String, margin: androidx.compose.ui.unit.Dp) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState()
    val refs by produceState(Elsewhere.found.get(name).orEmpty(), name) {
        value = Elsewhere.found.get(name) ?: OtherSites.client.sameCreator(name).also { Elsewhere.found.put(name, it) }
    }
    for (ref in refs.take(2)) androidx.compose.runtime.key(ref.url) {
        val videos by produceState(emptyList<VideoItem>(), ref) { value = OtherSites.videos(listOf(ref.url)) }
        val following = ref.url in settings.otherChannels
        Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = margin), verticalAlignment = Alignment.CenterVertically) {
                OrbitSectionHeader(stringResource(Res.string.video_elsewhere_title), ref.host, orbitFonts(), orbitLook(), Modifier.weight(1f))
                OrbitActionPill(
                    label = stringResource(if (following) Res.string.video_elsewhere_following else Res.string.video_elsewhere_follow),
                    onClick = {
                        graph.settings.update {
                            it.copy(otherChannels = if (following) it.otherChannels - ref.url else it.otherChannels + (ref.url to name))
                        }
                    },
                    fonts = orbitFonts(),
                    look = orbitLook(),
                    primary = !following,
                )
            }
            if (videos.isNotEmpty()) {
                VideoShelf(
                    title = name,
                    items = videos,
                    onItemClick = { item ->
                        OtherSites.play(item, graph, navigator, scope) { VideoActionsState.shared.showToast(ActionToastKind.NOT_PLAYABLE) }
                    },
                    onItemMore = {},
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
