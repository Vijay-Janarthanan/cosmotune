package com.cosmotune.video.ui.channel

import com.cosmotune.ui.orbit.OrbitPageHeader
import com.cosmotune.ui.orbit.OrbitHeaderSize
import com.cosmotune.ui.orbit.OrbitHeaderButton
import com.cosmotune.ui.orbit.OrbitHeaderSkeleton
import com.cosmotune.ui.orbit.OrbitCardColors
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
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitPlayButton
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
 * The head of a channel page (board frame 4): one colour card of about 200 dp in the colour of the channel's picture, its banner
 * faded into the card behind the words, the channel's disc beside the mono line ("CHANNEL · @HANDLE · 340 VIDEOS"), the name and
 * "1.2M subscribers · the first line of its description" (which opens About), then one row of round actions: Subscribe (filled),
 * Latest (download the latest uploads) and Share. Play stands in the bite and plays the latest upload. Then the tabs as chips.
 * Every block takes the side margin it should stand at ([margin]; the list or grid that holds it may pad the page already, see
 * PageLine). And the skeleton that stands in its place.
 */

/** The card's corner. */
private val CARD_SHAPE = RoundedCornerShape(34.dp)

/**
 * The channel's colour card (see the file's comment). The disc is 80 dp on a phone and 128 dp from 600 dp, always beside the
 * words. [preparing] disables the download while the uploads are being loaded for it; [onPlayLatest] (null while there is no
 * upload yet) is Play in the bite.
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
    onPlayLatest: (() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(channel.avatarUrl, channel.id)
    val handle = channel.handle?.takeIf { it.isNotBlank() }
    val subscribers = channel.subscriberCount?.let { stringResource(Res.string.video_subscribers, VideoText.compact(it)) }
    val videos = channel.videoCount?.let { count ->
        val quantity = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(Res.plurals.video_count_videos, quantity, quantity)
    }
    val label = listOfNotNull(stringResource(Res.string.video_channel_label), handle, videos).joinToString(" · ").uppercase()
    val description = channel.description?.lineSequence()?.firstOrNull { it.isNotBlank() }
    val latest = stringResource(Res.string.video_dl_latest)
    val share = stringResource(Res.string.video_share)
    val line = listOfNotNull(subscribers, description).joinToString(" · ").ifBlank { null }
    // The shared compact header (the board's channel card): the disc beside the words, the banner faded into the card behind them,
    // Subscribe, Latest and Share as the one row of actions, and Play (the latest upload) in the bite.
    OrbitPageHeader(
        cover = channel.avatarUrl, label = label, title = channel.name, subtitle = line, playing = false, onPlay = onPlayLatest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = margin).testTag("video:channel:card"),
        colors = OrbitCardColors(fill, ink), onSubtitle = onAbout,
        lead = { ChannelAvatar(channel.avatarUrl, OrbitHeaderSize.PAGE.disc, name = channel.name) },
        backdrop = if (bannerUrl == null) null else ({ colors ->
            val stand = LocalThumbnailStandIn.current
            if (stand != null) stand(bannerUrl, Modifier.fillMaxSize())
            else AsyncImage(model = bannerUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // The banner melts into the card so the words stay on its colour.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to colors.fill.copy(alpha = 0.55f), 0.55f to colors.fill.copy(alpha = 0.92f), 1f to colors.fill)))
        }),
    ) { colors ->
        SubscribePill(subscribed = subscribed, onClick = onSubscribe, ink = colors.ink, fill = colors.fill)
        Box(Modifier.graphicsLayer { alpha = if (preparing) 0.45f else 1f }) {
            OrbitHeaderButton(CosmoIcons.Download, latest, colors, { if (!preparing) onDownload() })
        }
        OrbitHeaderButton(CosmoIcons.Share, share, colors, onShare)
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

/** The header as it will be while it loads: the shared header's own placeholder ([OrbitHeaderSkeleton]). */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun ChannelHeaderSkeleton(wide: Boolean, widthDp: Float, margin: Dp) {
    OrbitHeaderSkeleton(Modifier.fillMaxWidth().padding(horizontal = margin))
}
