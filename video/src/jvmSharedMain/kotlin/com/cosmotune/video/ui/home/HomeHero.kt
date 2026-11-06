package com.cosmotune.video.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_home_up_next_for_you
import com.cosmotune.video.resources.video_in_watch_later
import com.cosmotune.video.resources.video_subscribed
import com.cosmotune.video.resources.video_views
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.watch.VideoRoomChip
import org.jetbrains.compose.resources.stringResource

/**
 * Home's colour card (Orbit board B): "Up next for you", the first video of the feed. One flat card in the colour of the video's
 * picture, the picture itself inside it (at the end from 600 dp, on top below), the mono line ("UP NEXT FOR YOU · 18:42 · 1.2M
 * VIEWS"), the title in the wide face, the channel's disc and name ("✓ Subscribed" when you follow it), and the card's chips:
 * Watch later and Watch together (where the host offers rooms). Play stands in the bite and opens the video ([onPlay]); the
 * channel opens with [onChannel], a hold on the card opens the video's sheet ([onMore]).
 */
@Composable
internal fun HomeUpNextCard(
    item: VideoItem,
    title: String,
    onPlay: () -> Unit,
    onChannel: (() -> Unit)?,
    onWatchLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val graph = LocalVideoGraph.current
    val later by graph.library.watchLater.collectAsState()
    val subscriptions by graph.library.subscriptions.collectAsState()
    val inLater = later.any { it.id == item.id }
    val subscribed = item.channelId != null && subscriptions.any { it.channelId == item.channelId }
    val (fill, ink) = coverFill(item.thumbnailUrl, item.id)
    val label = listOfNotNull(
        stringResource(Res.string.video_home_up_next_for_you),
        item.durationSec?.takeIf { it > 0 && !item.isLive }?.let { VideoText.duration(it) },
        item.viewCount?.takeIf { it > 0 }?.let { stringResource(Res.string.video_views, VideoText.compact(it)) },
    ).joinToString(" · ").uppercase()
    val subscribedText = stringResource(Res.string.video_subscribed)
    val laterText = stringResource(if (inLater) Res.string.video_in_watch_later else Res.string.video_watch_later)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        OrbitBiteCard(fill, Modifier.fillMaxWidth().testTag("video:home:upnext"), onClick = onPlay, bite = { OrbitPlayButton(false, onPlay, look) }) {
            val words: @Composable ColumnScope.() -> Unit = {
                Text(label, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    title, style = fonts.heroTitle.copy(fontSize = if (wide) 30.sp else 24.sp, lineHeight = if (wide) 32.sp else 26.sp), color = ink,
                    maxLines = if (wide) 2 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
                val name = item.channelName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    Row(
                        Modifier.padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(item.channelAvatarUrl, 36.dp, name = name)
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (subscribed) Text("  ✓ ${subscribedText.uppercase()}", style = fonts.label, color = ink.copy(alpha = 0.7f), maxLines = 1)
                    }
                }
                Row(
                    Modifier.padding(top = 18.dp, end = if (wide) 0.dp else 76.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WatchCardChip(laterText, ink, fonts, onWatchLater, icon = CosmoIcons.Clock, solid = inLater, onSolid = fill)
                    VideoRoomChip(item, ink, fonts)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().heightIn(min = 230.dp).padding(start = 26.dp, top = 24.dp, bottom = 24.dp, end = 112.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 24.dp), content = words)
                    ThumbnailFrame(item.thumbnailUrl, Modifier.width(340.dp).aspectRatio(16f / 9f), RoundedCornerShape(24.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    ThumbnailFrame(item.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(22.dp))
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 10.dp), content = words)
                }
            }
        }
    }
}

