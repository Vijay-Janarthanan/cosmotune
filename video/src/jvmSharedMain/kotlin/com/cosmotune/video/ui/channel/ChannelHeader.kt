package com.cosmotune.video.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.resources.video_channel_label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channel_about
import com.cosmotune.video.resources.video_channel_live
import com.cosmotune.video.resources.video_channel_playlists
import com.cosmotune.video.resources.video_channel_shorts
import com.cosmotune.video.resources.video_channel_videos
import com.cosmotune.video.resources.video_count_videos
import com.cosmotune.video.resources.video_dl_latest
import com.cosmotune.video.resources.video_share
import com.cosmotune.video.resources.video_subscribers
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.LocalThumbnailStandIn
import com.cosmotune.video.ui.blocks.SubscribePill
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind

/*
 * The head of a channel page (Orbit board B): one flat colour card in the colour of the channel's picture, with its banner as a
 * rounded strip at the top (nothing on it), the channel's disc, the mono line ("CHANNEL · @HANDLE · 1.2M SUBSCRIBERS"), the name in
 * the wide face, the first line of its description (which opens About) and its pills: Subscribe, Share, "Download the latest". Then
 * the tabs as chips. The card has no bite: a channel has no one thing to play. Every block takes the side margin it should stand
 * at ([margin]; the list or grid that holds it may pad the page already, see PageLine). And the skeleton that stands in its place.
 */

/** The card's corner and the banner's inside it. */
private val CARD_SHAPE = RoundedCornerShape(34.dp)
private val BANNER_SHAPE = RoundedCornerShape(24.dp)

/**
 * The channel's colour card (see the file's comment). From 600 dp the disc (128 dp) stands at the start and the words beside it; on
 * a phone the disc (88 dp) is over the words. [preparing] disables the download while the uploads are being loaded for it.
 */
@Composable
internal fun ChannelCard(
    channel: ChannelItem,
    bannerUrl: String?,
    subscribed: Boolean,
    wide: Boolean,
    margin: Dp,
    preparing: Boolean,
    onSubscribe: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onAbout: (() -> Unit)?,
) {
    val fonts = orbitFonts()
    val (fill, ink) = coverFill(channel.avatarUrl, channel.id)
    val handle = channel.handle?.takeIf { it.isNotBlank() }
    val subscribers = channel.subscriberCount?.let { stringResource(Res.string.video_subscribers, VideoText.compact(it)) }
    val videos = channel.videoCount?.let { count ->
        val quantity = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(Res.plurals.video_count_videos, quantity, quantity)
    }
    val label = listOfNotNull(stringResource(Res.string.video_channel_label), handle, subscribers, videos).joinToString(" · ").uppercase()
    val description = channel.description?.lineSequence()?.firstOrNull { it.isNotBlank() }
    val latest = stringResource(Res.string.video_dl_latest)
    Column(Modifier.fillMaxWidth().padding(horizontal = margin).clip(CARD_SHAPE).background(fill).padding(12.dp).testTag("video:channel:card")) {
        if (bannerUrl != null) {
            val stand = LocalThumbnailStandIn.current
            Box(Modifier.fillMaxWidth().height(if (wide) 132.dp else 84.dp).clip(BANNER_SHAPE).background(ink.copy(alpha = 0.08f))) {
                if (stand != null) stand(bannerUrl, Modifier.fillMaxSize())
                else AsyncImage(model = bannerUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        val words: @Composable ColumnScope.() -> Unit = {
            Text(label, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                channel.name, style = if (wide) fonts.greeting.copy(fontSize = 36.sp, lineHeight = 38.sp) else fonts.heroTitle.copy(fontSize = 26.sp, lineHeight = 28.sp),
                color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).semantics { heading() },
            )
            if (description != null) {
                Text(
                    description, style = fonts.body.copy(fontSize = 14.sp), color = ink.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).then(if (onAbout != null) Modifier.clickable(role = Role.Button, onClick = onAbout) else Modifier),
                )
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubscribePill(subscribed = subscribed, onClick = onSubscribe, ink = ink, fill = fill)
                OrbitRoundButton(CosmoIcons.Share, stringResource(Res.string.video_share), ink, onShare, onInk = fill)
                if (wide) {
                    WatchCardChip(latest, ink, fonts, onDownload, icon = CosmoIcons.Download, enabled = !preparing)
                } else {
                    Box(Modifier.graphicsLayer { alpha = if (preparing) 0.45f else 1f }) {
                        OrbitRoundButton(CosmoIcons.Download, latest, ink, { if (!preparing) onDownload() }, onInk = fill)
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(channel.avatarUrl, 128.dp, name = channel.name)
                Column(Modifier.weight(1f).padding(start = 28.dp), content = words)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 10.dp)) {
                ChannelAvatar(channel.avatarUrl, 88.dp, name = channel.name)
                Spacer(Modifier.height(16.dp))
                words()
            }
        }
    }
}

/** The tab chips (DESIGN_SYSTEM.md 6.2): one chip for each of [sections], [selected] chosen; the chips bring the 16 dp margin. */
@Composable
internal fun ChannelTabChips(sections: List<ChannelSection>, selected: ChannelSection, onSelect: (ChannelSection) -> Unit, modifier: Modifier = Modifier) {
    val labels = sections.map { sectionLabel(it) }
    VideoChips(labels = labels, selected = sections.indexOf(selected), onSelect = { onSelect(sections[it]) }, modifier = modifier)
}

@Composable
internal fun sectionLabel(section: ChannelSection): String = stringResource(
    when (section) {
        ChannelSection.VIDEOS -> Res.string.video_channel_videos
        ChannelSection.SHORTS -> Res.string.video_channel_shorts
        ChannelSection.LIVE -> Res.string.video_channel_live
        ChannelSection.PLAYLISTS -> Res.string.video_channel_playlists
        ChannelSection.ABOUT -> Res.string.video_channel_about
    },
)

/** The colour card as it will be, as a quiet shimmer block with the disc's place in it, while the header loads. */
@Composable
internal fun ChannelHeaderSkeleton(wide: Boolean, widthDp: Float, margin: Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = margin).clip(CARD_SHAPE).background(orbitLook().row).padding(24.dp)) {
        CosmoShimmer(Modifier.size(if (wide) 128.dp else 88.dp), CircleShape)
        CosmoShimmer(Modifier.padding(top = 16.dp).fillMaxWidth(0.6f).height(26.dp))
        CosmoShimmer(Modifier.padding(top = 8.dp).fillMaxWidth(0.4f).height(13.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CosmoShimmer(Modifier.size(width = 112.dp, height = 40.dp), CircleShape)
            CosmoShimmer(Modifier.size(40.dp), CircleShape)
            CosmoShimmer(Modifier.size(40.dp), CircleShape)
        }
    }
}
