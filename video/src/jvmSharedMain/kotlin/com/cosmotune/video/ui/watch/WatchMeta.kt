package com.cosmotune.video.ui.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_description
import com.cosmotune.video.resources.video_dislikes_cd
import com.cosmotune.video.resources.video_dl_done
import com.cosmotune.video.resources.video_dl_done_cd
import com.cosmotune.video.resources.video_download
import com.cosmotune.video.resources.video_in_watch_later
import com.cosmotune.video.resources.video_likes_cd
import com.cosmotune.video.resources.video_play_as_song
import com.cosmotune.video.resources.video_save
import com.cosmotune.video.resources.video_share
import com.cosmotune.video.resources.video_subscribe
import com.cosmotune.video.resources.video_subscribed
import com.cosmotune.video.resources.video_subscribers
import com.cosmotune.video.resources.video_theatre
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.MetaLine
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import com.cosmotune.video.ui.blocks.videoClickable
import org.jetbrains.compose.resources.stringResource

/*
 * The blocks under the player (spec 5.4, items 1 to 3): the title and the meta line, the action chips, and the channel row. Every
 * number is the spec's: the title `title(TITLE_S)` up to two lines in padding 12 16 0, the meta 13 sp `textSecondary` 6 dp below;
 * the chips 36 dp with 18 dp icons, 8 apart, in padding 12 16 4, scrolling sideways; the channel row in padding 10 16 with a 40 dp
 * avatar, 12 dp of gap, the name 15.5 sp 650 over the subscriber count 13 sp, and Subscribe (PRIMARY, SMALL) or Subscribed (TONAL,
 * SMALL) at its end. On the wide page the blocks take the side padding the page gives them, so they line up with the player.
 */

private val TITLE_TOP = 12.dp
private val META_TOP = 6.dp
private const val META_SP = 13f
private val CHIPS_TOP = 12.dp
private val CHIPS_BOTTOM = 4.dp
private val CHIPS_GAP = 8.dp
private val CHANNEL_PAD_V = 10.dp
private val CHANNEL_AVATAR = 40.dp
private val CHANNEL_GAP = 12.dp
private const val CHANNEL_NAME_SP = 15.5f
private const val CHANNEL_COUNT_SP = 13f

/** The chip's shape at rest: the B chip's rounded square (0.22 of its 36 dp height, `ControlMorph.chip`). */
private val CHIP_SHAPE = SuperellipseShape(ControlMorph.chip(ControlMetrics.CHIP_HEIGHT_DP, selected = false, pressed = false, floating = false).radiusDp.dp)

/** A chip under a finger. */
private const val CHIP_PRESSED_ALPHA = 0.7f

/** The chips under the title (spec 5.4), in their order. [THEATRE] is the wide page's own (spec 5.4: theatre mode). */
internal enum class WatchChip { LIKES, DISLIKES, SAVE, WATCH_LATER, DOWNLOAD, SHARE, PLAY_AS_SONG, THEATRE }

/**
 * The chips the page shows for [item] (spec 5.4, 5.13): the like count when known, the dislike count when known (null when the
 * setting is off or the page is offline), Save, Watch later, Download, Share and Play as a song; a live stream or a premiere that has
 * not started has nothing to download and no song to play, so it shows neither. [wide] adds the theatre chip.
 */
internal fun watchChips(item: VideoItem, likes: Long?, dislikes: Long?, wide: Boolean): List<WatchChip> = buildList {
    val streamable = !item.isLive && !item.isUpcoming
    if (likes != null) add(WatchChip.LIKES)
    if (dislikes != null) add(WatchChip.DISLIKES)
    add(WatchChip.SAVE)
    add(WatchChip.WATCH_LATER)
    if (streamable) add(WatchChip.DOWNLOAD)
    add(WatchChip.SHARE)
    if (streamable) add(WatchChip.PLAY_AS_SONG)
    if (wide) add(WatchChip.THEATRE)
}

/**
 * What the blocks show of the video: [item] (the fuller one, from the details when they came), [title] as shown (DeArrow's when
 * on), the counts, the channel's subscriber count, whether you follow it and keep the video for later, and [downloadedLabel], the
 * quality of a finished download ("720p"), or null.
 */
@Immutable
internal data class WatchInfo(
    val item: VideoItem,
    val title: String,
    val likes: Long?,
    val dislikes: Long?,
    val subscriberCount: Long?,
    val subscribed: Boolean,
    val inWatchLater: Boolean,
    val downloadedLabel: String?,
)

/**
 * The title, the meta line, the chips and the channel row (spec 5.4). [onOpenDescription] is the tap on the title and meta (the
 * description sheet on the phone; null on the wide page, whose description stands inline). [theatre] marks the theatre chip.
 */
@Composable
internal fun WatchMeta(
    info: WatchInfo,
    wide: Boolean,
    theatre: Boolean,
    sidePadding: Dp,
    onOpenDescription: (() -> Unit)?,
    onChip: (WatchChip) -> Unit,
    onOpenChannel: () -> Unit,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        WatchTitle(info, sidePadding, onOpenDescription)
        WatchActionChips(info, wide, theatre, sidePadding, onChip)
        WatchChannelRow(info, sidePadding, onOpenChannel, onSubscribe)
    }
}

/** The title up to two lines and the meta line ("1.2M views · 3 days ago"); one button that opens the description when [onOpen] is set. */
@Composable
private fun WatchTitle(info: WatchInfo, sidePadding: Dp, onOpen: (() -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val titleStyle = remember(type, info.title) { type.forText(type.title(TypeScale.TITLE_S), info.title) }
    val meta = MetaLine(info.item, rememberNowMs(), showChannel = false)
    val metaStyle = rememberMetaStyle(META_SP, meta)
    val openLabel = stringResource(Res.string.video_description)
    val tap = if (onOpen != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen) else Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .then(tap)
            .padding(start = sidePadding, end = sidePadding, top = TITLE_TOP),
    ) {
        Text(info.title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = metaStyle,
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = META_TOP),
            )
        }
    }
}

/** The chips (spec 5.4): 36 dp, 8 apart, in padding 12 16 4, scrolling sideways. */
@Composable
private fun WatchActionChips(info: WatchInfo, wide: Boolean, theatre: Boolean, sidePadding: Dp, onChip: (WatchChip) -> Unit) {
    val chips = remember(info.item.id, info.item.isLive, info.item.isUpcoming, info.likes, info.dislikes, wide) {
        watchChips(info.item, info.likes, info.dislikes, wide)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = sidePadding, end = sidePadding, top = CHIPS_TOP, bottom = CHIPS_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(CHIPS_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in chips) {
            when (chip) {
                WatchChip.LIKES -> {
                    val count = VideoText.compact(info.likes ?: 0)
                    WatchActionChip(VideoIcons.ThumbUp, count, stringResource(Res.string.video_likes_cd, count), onClick = null)
                }
                WatchChip.DISLIKES -> {
                    val count = VideoText.compact(info.dislikes ?: 0)
                    WatchActionChip(VideoIcons.ThumbDown, count, stringResource(Res.string.video_dislikes_cd, count), onClick = null)
                }
                WatchChip.SAVE -> WatchActionChip(VideoIcons.PlaylistAdd, stringResource(Res.string.video_save), null, onClick = { onChip(chip) })
                WatchChip.WATCH_LATER -> WatchActionChip(
                    icon = CosmoIcons.Clock,
                    label = stringResource(if (info.inWatchLater) Res.string.video_in_watch_later else Res.string.video_watch_later),
                    description = null,
                    onClick = { onChip(chip) },
                    on = info.inWatchLater,
                )
                WatchChip.DOWNLOAD -> {
                    val done = info.downloadedLabel
                    val label = if (done != null) {
                        stringResource(Res.string.video_dl_done, stringResource(Res.string.video_dl_done_cd), done)
                    } else {
                        stringResource(Res.string.video_download)
                    }
                    WatchActionChip(if (done != null) CosmoIcons.Downloaded else CosmoIcons.Download, label, null, onClick = { onChip(chip) }, on = done != null)
                }
                WatchChip.SHARE -> WatchActionChip(CosmoIcons.Share, stringResource(Res.string.video_share), null, onClick = { onChip(chip) })
                WatchChip.PLAY_AS_SONG -> WatchActionChip(CosmoIcons.Waveform, stringResource(Res.string.video_play_as_song), null, onClick = { onChip(chip) })
                WatchChip.THEATRE -> WatchActionChip(null, stringResource(Res.string.video_theatre), null, onClick = { onChip(chip) }, on = theatre)
            }
        }
    }
}

/**
 * One action chip (spec 5.4): 36 dp, an 18 dp [icon] and the [label], the B chip's look (a faint ink wash and a 1 dp outline; [on]:
 * the tonal container with its ink). `CosmoChip` is a filter chip with no icon, so the watch page draws its own. A chip with no
 * [onClick] is a figure (the like and dislike counts), read by its [description]; the others are buttons with a 48 dp target and the
 * focus ring. The fills are read while drawing.
 */
@Composable
internal fun WatchActionChip(
    icon: ImageVector?,
    label: String,
    description: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    on: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val ink = Color(if (on) palette.onAccentContainer else palette.textPrimary)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val action = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .cosmoFocusRing(interaction, CHIP_RADIUS)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier.clearAndSetSemantics { if (description != null) contentDescription = description }
    }
    Row(
        modifier
            .then(action)
            .height(ControlMetrics.CHIP_HEIGHT_DP.dp)
            .graphicsLayer { alpha = if (pressed) CHIP_PRESSED_ALPHA else 1f }
            .drawWithCache {
                val outline = CHIP_SHAPE.createOutline(size, layoutDirection, this)
                val stroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    if (on) {
                        drawOutline(outline, colors.live(CoverField.ACCENT_CONTAINER))
                    } else {
                        val inkLive = colors.live(CoverField.TEXT_PRIMARY)
                        drawOutline(outline, inkLive.copy(alpha = ControlMetrics.CHIP_REST_FILL))
                        drawOutline(outline, inkLive.copy(alpha = ControlMetrics.OUTLINE), style = stroke)
                    }
                }
            }
            .padding(horizontal = ControlMetrics.CHIP_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ControlMetrics.CHIP_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.CHIP_ICON_DP.dp))
        Text(
            text = label,
            color = ink,
            style = type.forText(type.label.copy(fontSize = ControlMetrics.chipFontSp(false).sp, fontWeight = FontWeight(CHIP_WEIGHT)), label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The chip label's weight (`.chip2`, as `CosmoChip`). */
private const val CHIP_WEIGHT = 620

/** The focus ring's corner around a chip. */
private val CHIP_RADIUS = CosmoRadii.xs

/**
 * The channel row (spec 5.4): the avatar, the name over the subscriber count, and Subscribe or Subscribed. The row opens the channel
 * when the video has one.
 */
@Composable
private fun WatchChannelRow(info: WatchInfo, sidePadding: Dp, onOpenChannel: () -> Unit, onSubscribe: () -> Unit) {
    val item = info.item
    val name = item.channelName?.takeIf { it.isNotBlank() } ?: return
    val palette = LocalCosmoColors.current.palette
    val nameStyle = rememberTitleStyle(CHANNEL_NAME_SP, name)
    val count = info.subscriberCount?.let { stringResource(Res.string.video_subscribers, VideoText.compact(it)) }
    val countStyle = rememberMetaStyle(CHANNEL_COUNT_SP, count.orEmpty())
    val open = if (item.channelId != null) Modifier.videoClickable(CosmoRadii.md, onOpenChannel, null) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding, vertical = CHANNEL_PAD_V),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).then(open).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(item.channelAvatarUrl, CHANNEL_AVATAR)
            Spacer(Modifier.width(CHANNEL_GAP))
            Column(Modifier.weight(1f)) {
                Text(name, style = nameStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count != null) Text(count, style = countStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.channelId != null) {
            Spacer(Modifier.width(CHANNEL_GAP))
            CosmoButton(
                text = stringResource(if (info.subscribed) Res.string.video_subscribed else Res.string.video_subscribe),
                onClick = onSubscribe,
                kind = if (info.subscribed) CosmoButtonKind.TONAL else CosmoButtonKind.PRIMARY,
                size = CosmoButtonSize.SMALL,
            )
        }
    }
}
