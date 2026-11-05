package com.cosmotune.video.ui.channel

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
import androidx.compose.ui.draw.drawWithContent
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
 * The head of a channel page (spec 5.5): the banner, the avatar with the name and the counts, the actions and the tab chips; and the
 * skeleton that stands in their place while the header loads. Every block takes the side margin it should stand at ([margin]; the
 * list or grid that holds it may pad the page already, see PageLine).
 */

/** The wide page's banner: `rMd` inside the page margin. */
private val WIDE_BANNER_SHAPE = RoundedCornerShape(CosmoRadii.rMd)

/** The banner's lower 40 % fades into the page's ground (the one content gradient Video has, DESIGN_SYSTEM.md 2.3): the fade starts at 60 % of the height. */
private const val BANNER_FADE_FROM = 0.60f

/** How far the avatar stands over the banner (DESIGN_SYSTEM.md 6.2). */
private val AVATAR_OVERLAP = 24.dp

/**
 * The banner (DESIGN_SYSTEM.md 6.2). Phone: edge to edge, 16:5 ([VideoMetrics.channelBannerHeightDp] of [widthDp]), its lower 40 % fading into
 * the page's `backgroundTop` (drawn over the picture in `drawWithContent`). Wide: [VideoMetrics.DESKTOP_BANNER_DP] tall with `rMd` inside
 * [margin], no fade. Behind the picture, and when the channel has none, the placeholder fill.
 */
@Composable
internal fun ChannelBanner(url: String?, wide: Boolean, widthDp: Float, margin: Dp) {
    val colors = LocalCosmoColors.current
    val frame = if (wide) {
        Modifier.padding(horizontal = margin).fillMaxWidth().height(VideoMetrics.DESKTOP_BANNER_DP.dp).clip(WIDE_BANNER_SHAPE)
    } else {
        Modifier.fillMaxWidth().height(VideoMetrics.channelBannerHeightDp(widthDp).dp)
    }
    Box(
        frame
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) }
            .drawWithContent {
                drawContent()
                if (!wide) {
                    val ground = colors.live(CoverField.BACKGROUND_TOP)
                    drawRect(Brush.verticalGradient(BANNER_FADE_FROM to ground.copy(alpha = 0f), 1f to ground))
                }
            },
    ) {
        val stand = LocalThumbnailStandIn.current
        if (url != null && stand != null) {
            stand(url, Modifier.fillMaxSize())
        } else if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The head (DESIGN_SYSTEM.md 6.2): the avatar (72 dp; 96 on a wide page) overlapping the banner by 24 dp, the name in `headline`, "@handle ·
 * 1.2M subscribers · 340 videos" in `meta`, and one [ChannelItem.description] line that opens the channel's About ([onAbout], when it has one).
 */
@Composable
internal fun ChannelHead(channel: ChannelItem, wide: Boolean, margin: Dp, onAbout: (() -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val avatar = (if (wide) VideoMetrics.CHANNEL_AVATAR_DESKTOP_DP else VideoMetrics.CHANNEL_AVATAR_DP).dp
    val handle = channel.handle?.takeIf { it.isNotBlank() }
    val subscribers = channel.subscriberCount?.let { stringResource(Res.string.video_subscribers, VideoText.compact(it)) }
    val videos = channel.videoCount?.let { count ->
        val quantity = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(Res.plurals.video_count_videos, quantity, quantity)
    }
    val meta = listOfNotNull(handle, subscribers, videos).joinToString(" · ")
    val description = channel.description?.lineSequence()?.firstOrNull { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().padding(horizontal = margin)) {
        ChannelAvatar(channel.avatarUrl, avatar, Modifier.overlapUp(AVATAR_OVERLAP), name = channel.name)
        Text(
            text = channel.name,
            style = type.forText(type.headline, channel.name),
            color = Color(palette.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        if (meta.isNotEmpty()) MetaText(meta, Modifier.padding(top = 2.dp))
        if (description != null) {
            Text(
                text = description,
                style = type.forText(type.bodyText, description),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().then(if (onAbout != null) Modifier.clickable(role = Role.Button, onClick = onAbout) else Modifier),
            )
        }
    }
}

/** Draws the content [by] higher than its place and takes that much less room, so what follows comes up with it. */
private fun Modifier.overlapUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = by.roundToPx()
    layout(placeable.width, (placeable.height - up).coerceAtLeast(0)) { placeable.place(0, -up) }
}

/** A `meta` line in `textSecondary`. */
@Composable
private fun MetaText(text: String, modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    Text(
        text = text,
        style = rememberMetaStyle(text),
        color = Color(palette.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The actions (DESIGN_SYSTEM.md 6.2): Subscribe (the accent; tonal once subscribed), Share (a 40 dp tonal icon button), and "Download the latest"
 * (a tonal icon button on the phone, a labelled tonal button on a wide page), 8 dp apart, in padding 12 [margin] 4. [preparing] disables the
 * download while the uploads are being loaded for it.
 */
@Composable
internal fun ChannelActions(
    subscribed: Boolean,
    wide: Boolean,
    margin: Dp,
    preparing: Boolean,
    onSubscribe: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
) {
    val latest = stringResource(Res.string.video_dl_latest)
    Row(
        Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // On a phone the button takes the width the icons leave, as in the video apps.
        SubscribePill(subscribed = subscribed, onClick = onSubscribe, modifier = if (wide) Modifier else Modifier.weight(1f))
        CosmoIconButton(
            icon = CosmoIcons.Share,
            contentDescription = stringResource(Res.string.video_share),
            onClick = onShare,
            style = CosmoIconButtonStyle.TONAL,
            size = 40.dp,
        )
        if (wide) {
            KitButton(text = latest, onClick = onDownload, kind = KitButtonKind.TONAL, dense = true, icon = CosmoIcons.Download, enabled = !preparing)
        } else {
            CosmoIconButton(
                icon = CosmoIcons.Download,
                contentDescription = latest,
                onClick = onDownload,
                style = CosmoIconButtonStyle.TONAL,
                size = 40.dp,
                enabled = !preparing,
            )
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

/** The banner, the head and the actions as they will be, in shimmer blocks, while the header loads. */
@Composable
internal fun ChannelHeaderSkeleton(wide: Boolean, widthDp: Float, margin: Dp) {
    val avatar = (if (wide) VideoMetrics.CHANNEL_AVATAR_DESKTOP_DP else VideoMetrics.CHANNEL_AVATAR_DP).dp
    Column(Modifier.fillMaxWidth()) {
        if (wide) {
            CosmoShimmer(Modifier.padding(horizontal = margin).fillMaxWidth().height(VideoMetrics.DESKTOP_BANNER_DP.dp), WIDE_BANNER_SHAPE)
        } else {
            CosmoShimmer(Modifier.fillMaxWidth().height(VideoMetrics.channelBannerHeightDp(widthDp).dp), androidx.compose.ui.graphics.RectangleShape)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = margin)) {
            CosmoShimmer(Modifier.size(avatar).overlapUp(AVATAR_OVERLAP), CircleShape)
            CosmoShimmer(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(26.dp))
            CosmoShimmer(Modifier.padding(top = 8.dp).fillMaxWidth(0.4f).height(13.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CosmoShimmer(Modifier.size(width = 112.dp, height = 40.dp), CircleShape)
            CosmoShimmer(Modifier.size(40.dp), CircleShape)
            CosmoShimmer(Modifier.size(40.dp), CircleShape)
        }
    }
}
