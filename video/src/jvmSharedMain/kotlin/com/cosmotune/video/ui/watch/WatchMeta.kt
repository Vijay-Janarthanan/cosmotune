package com.cosmotune.video.ui.watch

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.account.Rating
import com.cosmotune.video.graph.LocalVideoGraph
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
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
import com.cosmotune.video.resources.video_subscribers
import com.cosmotune.video.resources.video_theatre
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.SubscribePill
import com.cosmotune.video.ui.blocks.MetaLine
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import com.cosmotune.video.ui.blocks.videoClickable
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.cosmotune.ui.kit.KitActionRow
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.roomVideoItem
import com.cosmotune.video.resources.video_show_more
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The blocks under the player (DESIGN_SYSTEM.md 6.2): the title `titleMedium` up to two lines, the meta line with "…more"; the compact 56 dp
 * channel row with Subscribe; and one sideways-scrolling row of tonal actions, which never wraps. On the wide page the blocks take the side
 * padding the page gives them, so they line up with the player.
 */

private val TITLE_TOP = 12.dp
private val META_TOP = 2.dp
private val CHANNEL_ROW = 56.dp
private val CHANNEL_AVATAR = 40.dp
private val CHANNEL_GAP = 12.dp
private val ACTIONS_BOTTOM = 8.dp
private val ACTION_HEIGHT = 40.dp
private val ACTION_ICON = 20.dp
private val ACTION_SHAPE = RoundedCornerShape(50)

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
        // The channel comes before the actions, as the video apps lay it out.
        WatchChannelRow(info, sidePadding, onOpenChannel, onSubscribe)
        WatchActionChips(info, wide, theatre, sidePadding, onChip)
    }
}

/** The title up to two lines and the meta line ("1.2M views · 3 days ago  …more"); one button that opens the description when [onOpen] is set. */
@Composable
private fun WatchTitle(info: WatchInfo, sidePadding: Dp, onOpen: (() -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val titleStyle = remember(type, info.title) { type.forText(type.titleMedium, info.title) }
    val more = stringResource(Res.string.video_show_more)
    val line = MetaLine(info.item, rememberNowMs(), showChannel = false)
    // The tap is on the whole block; the words say so where there is a sheet to open.
    val meta = if (onOpen != null) (if (line.isEmpty()) "…$more" else "$line  …$more") else line
    val metaStyle = rememberMetaStyle(meta)
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

/**
 * The actions (DESIGN_SYSTEM.md 4.12, 6.2): one row of 40 dp tonal pills that scrolls sideways and never wraps, on the phone and on a wide page,
 * where the mouse wheel moves it too (a mouse has no other way to reach the end of it).
 */
@Composable
private fun WatchActionChips(info: WatchInfo, wide: Boolean, theatre: Boolean, sidePadding: Dp, onChip: (WatchChip) -> Unit) {
    val chips = remember(info.item.id, info.item.isLive, info.item.isUpcoming, info.likes, info.dislikes, wide) {
        watchChips(info.item, info.likes, info.dislikes, wide)
    }
    // Signed in, the like and dislike chips rate the video on the account (a second tap clears it).
    val account = LocalVideoGraph.current.account
    val signedIn = account.state.collectAsState().value is AccountState.SignedIn
    val scope = rememberCoroutineScope()
    var rating by remember(info.item.id) { mutableStateOf(Rating.NONE) }
    fun rate(next: Rating) {
        val target = if (rating == next) Rating.NONE else next
        scope.launch { if (account.rate(info.item.id, target).isSuccess) rating = target }
    }
    val chipItems: @Composable () -> Unit = {
        for (chip in chips) {
            when (chip) {
                WatchChip.LIKES -> {
                    val count = VideoText.compact(info.likes ?: 0)
                    val like: @Composable (Boolean) -> Unit = { segment ->
                        WatchActionChip(
                            VideoIcons.ThumbUp, count, stringResource(Res.string.video_likes_cd, count),
                            onClick = if (signedIn) ({ rate(Rating.LIKE) }) else null, on = rating == Rating.LIKE, segment = segment,
                        )
                    }
                    if (WatchChip.DISLIKES in chips) {
                        // Like and dislike are one segmented pill, as the video apps draw them.
                        val dislikes = VideoText.compact(info.dislikes ?: 0)
                        WatchRatingSegment(
                            like = { like(true) },
                            dislike = {
                                WatchActionChip(
                                    VideoIcons.ThumbDown, dislikes, stringResource(Res.string.video_dislikes_cd, dislikes),
                                    onClick = if (signedIn) ({ rate(Rating.DISLIKE) }) else null, on = rating == Rating.DISLIKE, segment = true,
                                )
                            },
                        )
                    } else {
                        like(false)
                    }
                }
                WatchChip.DISLIKES -> if (WatchChip.LIKES !in chips) {
                    val count = VideoText.compact(info.dislikes ?: 0)
                    WatchActionChip(
                        VideoIcons.ThumbDown, count, stringResource(Res.string.video_dislikes_cd, count),
                        onClick = if (signedIn) ({ rate(Rating.DISLIKE) }) else null, on = rating == Rating.DISLIKE,
                    )
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
    // The video for everyone in the room, each device playing it in its own player. Under the original title (not DeArrow's), since
    // the others may not use it. Drawn only where the host offers rooms.
    val roomItem = remember(info.item.id, info.item.title, info.item.channelName, info.item.thumbnailUrl, info.item.durationSec) {
        roomVideoItem(info.item.id, info.item.title, info.item.channelName.orEmpty(), info.item.thumbnailUrl, info.item.durationSec?.times(1000L))
    }
    val scroll = rememberScrollState()
    KitActionRow(
        modifier = Modifier.padding(bottom = ACTIONS_BOTTOM).wheelScrollsSideways(scroll),
        contentPadding = PaddingValues(horizontal = sidePadding),
        scrollState = scroll,
    ) {
        RoomPlayButton(roomItem, dense = true)
        chipItems()
    }
}

/** A mouse wheel over a sideways row moves it, as long as it can move; at its ends the wheel goes on to the page. */
private fun Modifier.wheelScrollsSideways(scroll: ScrollState): Modifier = pointerInput(scroll) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val delta = change.scrollDelta.y * WHEEL_STEP.toPx()
            if (scroll.dispatchRawDelta(delta) != 0f) change.consume()
        }
    }
}

/** How far one notch of the wheel moves the row. */
private val WHEEL_STEP = 48.dp

/**
 * One action (DESIGN_SYSTEM.md 4.12): a 40 dp tonal pill (`surface2`), a 20 dp [icon] and the [label]; [on] (a rating given, a video kept) is the
 * neutral `surface3`, never the accent. An action with no [onClick] is a figure (the like and dislike counts while signed out), read by its
 * [description]; the others are buttons with a 48 dp target and the focus ring. [segment]: half of the rating pill, which draws the fill.
 */
@Composable
internal fun WatchActionChip(
    icon: ImageVector?,
    label: String,
    description: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    segment: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val ink = Color(palette.textPrimary)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val action = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .cosmoFocusRing(interaction, ACTION_HEIGHT / 2)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier.clearAndSetSemantics { if (description != null) contentDescription = description }
    }
    val fill = when {
        on -> Modifier.background(Color(palette.surfaceVariant), ACTION_SHAPE)
        segment -> Modifier
        else -> Modifier.background(Color(palette.surfaceHigh), ACTION_SHAPE)
    }
    Row(
        modifier
            .then(action)
            .height(ACTION_HEIGHT)
            .graphicsLayer { alpha = if (pressed) CHIP_PRESSED_ALPHA else 1f }
            .then(fill)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(ACTION_ICON))
        Text(
            text = label,
            color = ink,
            style = type.forText(type.control, label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Like and dislike as one tonal pill with a hairline between the halves; each half is a [WatchActionChip] drawn as a segment. */
@Composable
private fun WatchRatingSegment(like: @Composable () -> Unit, dislike: @Composable () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    Row(
        Modifier.height(ACTION_HEIGHT).background(Color(palette.surfaceHigh), ACTION_SHAPE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        like()
        Spacer(Modifier.width(1.dp).height(SEGMENT_RULE).background(Color(palette.textPrimary).copy(alpha = SEGMENT_RULE_ALPHA)))
        dislike()
    }
}

private val SEGMENT_RULE = 20.dp
private const val SEGMENT_RULE_ALPHA = 0.16f

/**
 * The channel row (DESIGN_SYSTEM.md 6.2): 56 dp, the avatar, the name over the subscriber count, and Subscribe (the accent; tonal once
 * subscribed). The row opens the channel when the video has one.
 */
@Composable
private fun WatchChannelRow(info: WatchInfo, sidePadding: Dp, onOpenChannel: () -> Unit, onSubscribe: () -> Unit) {
    val item = info.item
    val name = item.channelName?.takeIf { it.isNotBlank() } ?: return
    val palette = LocalCosmoColors.current.palette
    val nameStyle = rememberTitleStyle(name)
    val count = info.subscriberCount?.let { stringResource(Res.string.video_subscribers, VideoText.compact(it)) }
    val countStyle = rememberMetaStyle(count.orEmpty())
    val open = if (item.channelId != null) Modifier.videoClickable(CosmoRadii.rMd, onOpenChannel, null) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CHANNEL_ROW)
            .padding(horizontal = sidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).then(open).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(item.channelAvatarUrl, CHANNEL_AVATAR, name = item.channelName)
            Spacer(Modifier.width(CHANNEL_GAP))
            Column(Modifier.weight(1f)) {
                Text(name, style = nameStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count != null) Text(count, style = countStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.channelId != null) {
            Spacer(Modifier.width(CHANNEL_GAP))
            SubscribePill(subscribed = info.subscribed, onClick = onSubscribe)
        }
    }
}
