package com.cosmotune.ui.room

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSeekBar
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitArtwork
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import org.jetbrains.compose.resources.stringResource

/**
 * Now Playing in the room: the cover, the title and who started it, the shared playhead, the transport and the shared queue, then
 * "Ask to play…". The controls stay where they are when the host has locked them and are drawn disabled with the reason under
 * them, so nobody wonders why a tap did nothing.
 *
 * The same page plays every kind of item: a video shows the platform's [media] surface at 16:9 (or its thumbnail), a live station
 * has no playhead and says so, and a video or a podcast offers this device's own playback options.
 */
@Composable
internal fun RoomNowPlayingPage(
    state: RoomUiState,
    room: RoomState,
    actions: RoomActions,
    wide: Boolean,
    media: (@Composable () -> Unit)? = null,
) {
    // The cover gives way to the controls: at most about two fifths of the page's height, so the transport is in view without
    // scrolling on a short phone or a low desktop window.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverMax = (maxHeight * 0.42f).coerceIn(160.dp, if (wide) 360.dp else 320.dp)
        // A video is 16:9 at the same height as a cover would be, so it is wider than a cover but never pushes the transport away.
        val videoMax = (coverMax * (16f / 9f)).coerceAtMost(maxWidth)
        NowPlayingList(state, room, actions, coverMax, videoMax, media)
    }
}

@Composable
private fun NowPlayingList(
    state: RoomUiState,
    room: RoomState,
    actions: RoomActions,
    coverMax: Dp,
    videoMax: Dp,
    media: (@Composable () -> Unit)?,
) {
    val track = room.playback.track
    val queue = room.queue.items
    val canControl = state.canControl
    val kind = track?.kind?.takeIf { it in PartyTrack.KINDS } ?: PartyTrack.KIND_MUSIC
    val live = track?.isLive == true
    LazyColumn(
        Modifier.fillMaxSize().testTag(RoomTags.PAGE_NOW_PLAYING),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (track == null) {
            item(key = "empty") { NothingPlaying(canControl, actions) }
        } else {
            if (kind == PartyTrack.KIND_VIDEO) {
                item(key = "video") { VideoFrame(track, videoMax, media) }
            } else {
                item(key = "cover") { Cover(track, coverMax) }
            }
            item(key = "titles") {
                Titles(track, kind, roomStartedByName(room.playback.startedByName, room.playback.startedBy, room.members))
            }
            // A live station has no playhead to share: everyone is simply "now", so there is nothing to seek and no time to show.
            if (live) {
                item(key = "live") { LiveNote() }
            } else {
                item(key = "seek") { Playhead(state.positionMs, track.durationMs, canControl, actions) }
            }
            item(key = "transport") {
                // A station has a previous and a next only when the shared queue has one either side of it.
                val index = room.queue.index
                Transport(
                    room.playback.isPlaying,
                    canControl,
                    actions,
                    showPrevious = !live || index > 0,
                    showNext = !live || (index >= 0 && index < queue.lastIndex),
                )
            }
            // Quality, captions and speed are each person's own, so this is open to everyone, host-only room or not.
            if (kind == PartyTrack.KIND_VIDEO || kind == PartyTrack.KIND_PODCAST) {
                item(key = "options") { PlaybackOptions(actions) }
            }
            item(key = "ask") {
                KitButton(
                    stringResource(Res.string.cosmo_room_ask_to_play),
                    actions::askToPlay,
                    kind = KitButtonKind.TONAL,
                    icon = CosmoIcons.Plus,
                    modifier = Modifier.padding(top = 8.dp).testTag(RoomTags.ASK_TO_PLAY),
                )
            }
        }
        item(key = "queue-head") { QueueHeader(canControl && queue.isNotEmpty(), actions) }
        if (queue.isEmpty()) {
            item(key = "queue-empty") {
                Text(
                    stringResource(Res.string.cosmo_room_queue_empty),
                    style = LocalCosmoType.current.meta,
                    color = Color(LocalCosmoColors.current.palette.textSecondary),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 8.dp),
                )
            }
        }
        // A queue may hold the same song twice, so the key is its place as well as its id.
        itemsIndexed(queue, key = { index, item -> "q:$index:${item.videoId}" }) { index, item ->
            QueueRow(item, index, current = index == room.queue.index, canControl = canControl, actions = actions)
        }
    }
}

@Composable
private fun NothingPlaying(canControl: Boolean, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CosmoIcons.Together, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(40.dp))
        Text(
            stringResource(Res.string.cosmo_room_nothing_playing),
            style = type.titleMedium,
            color = Color(palette.textPrimary),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (!canControl) HostOnlyHint()
        KitButton(
            stringResource(Res.string.cosmo_room_ask_to_play),
            actions::askToPlay,
            kind = KitButtonKind.TONAL,
            icon = CosmoIcons.Plus,
            modifier = Modifier.testTag(RoomTags.ASK_TO_PLAY),
        )
    }
}

/** The cover at most [max] across, the page's width less its margins below that; the quiet surface while it loads. */
@Composable
private fun Cover(track: PartyTrack, max: Dp) {
    val palette = LocalCosmoColors.current.palette
    Box(
        Modifier
            .padding(horizontal = 32.dp, vertical = 12.dp)
            .widthIn(max = max)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(CosmoRadii.coverNowPlaying))
            .background(Color(palette.surfaceVariant)),
    ) {
        if (track.thumbnailUrl != null) {
            AsyncImage(model = track.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * A video at 16:9, at most [max] across: the platform's own surface from [media] when it gave one (each device plays the video itself),
 * else the thumbnail on the quiet surface.
 */
@Composable
private fun VideoFrame(track: PartyTrack, max: Dp, media: (@Composable () -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    Box(
        Modifier
            .padding(horizontal = CosmoSpacing.margin, vertical = 12.dp)
            .widthIn(max = max)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .background(if (media != null) Color.Black else Color(palette.surfaceVariant))
            .testTag(RoomTags.VIDEO),
        contentAlignment = Alignment.Center,
    ) {
        when {
            media != null -> media()
            track.thumbnailUrl != null ->
                AsyncImage(model = track.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
private fun Titles(track: PartyTrack, kind: String, startedBy: String?) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin + 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (track.isLive) LiveBadge(Modifier.padding(bottom = 4.dp))
        Text(
            track.title,
            style = type.forText(type.playerTitle, track.title),
            color = Color(palette.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (track.artist.isNotBlank()) {
            Text(track.artist, style = type.forText(type.bodyText, track.artist), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Music needs no word for what it is; the other kinds say so, since the same page plays all of them.
        val kindLabel = when (kind) {
            PartyTrack.KIND_VIDEO -> Res.string.cosmo_room_kind_video
            PartyTrack.KIND_RADIO -> Res.string.cosmo_room_kind_radio
            PartyTrack.KIND_PODCAST -> Res.string.cosmo_room_kind_podcast
            else -> null
        }
        if (kindLabel != null) {
            Text(
                stringResource(kindLabel),
                style = type.meta,
                color = Color(palette.textSecondary),
                maxLines = 1,
                modifier = Modifier.testTag(RoomTags.KIND),
            )
        }
        if (startedBy != null) {
            Text(stringResource(Res.string.cosmo_room_started_by, startedBy), style = type.meta, color = Color(palette.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The shared playhead. While a finger holds it the bar follows the finger, not the room, so the room's once-a-second updates do not
 * yank it back; letting go seeks the room. Without control (or without a known length) it is a plain progress line.
 */
@Composable
private fun Playhead(positionMs: Long, durationMs: Long?, canControl: Boolean, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shownFraction = scrub ?: roomPositionFraction(positionMs, durationMs)
    val shownMs = if (scrub != null && durationMs != null) roomSeekTarget(scrub!!, durationMs) else positionMs
    Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin + 8.dp, vertical = 4.dp)) {
        if (canControl && durationMs != null && durationMs > 0L) {
            CosmoSeekBar(
                fraction = { scrub ?: roomPositionFraction(positionMs, durationMs) },
                onScrub = { scrub = it },
                onRelease = {
                    val at = scrub
                    scrub = null
                    if (at != null) actions.seekTo(roomSeekTarget(at, durationMs))
                },
                modifier = Modifier.testTag(RoomTags.SEEK),
            )
        } else {
            ProgressTrack(shownFraction)
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatRoomClock(shownMs), style = type.caption.tabular(), color = Color(palette.textSecondary))
            Spacer(Modifier.weight(1f))
            if (durationMs != null && durationMs > 0L) {
                Text(formatRoomClock(durationMs), style = type.caption.tabular(), color = Color(palette.textSecondary))
            }
        }
    }
}

/** The accent "LIVE" pill a station carries, as Podio marks its own. */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(palette.accent))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(RoomTags.LIVE_BADGE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color(palette.onAccent)))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.cosmo_room_live_badge), style = type.tabLabel, color = Color(palette.onAccent), maxLines = 1)
    }
}

/** Where the playhead would be on a live station: one line saying there is nothing to line up. */
@Composable
private fun LiveNote() {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        stringResource(Res.string.cosmo_room_live_note),
        style = type.meta,
        color = Color(palette.textSecondary),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin + 8.dp, vertical = 12.dp).testTag(RoomTags.LIVE_NOTE),
    )
}

/** This device's own quality, captions and speed: a quiet pill under the transport, with a line that says it changes nobody else's. */
@Composable
private fun PlaybackOptions(actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KitButton(
            stringResource(Res.string.cosmo_room_playback_options),
            actions::openPlaybackOptions,
            kind = KitButtonKind.QUIET,
            icon = CosmoIcons.Settings,
            dense = true,
            modifier = Modifier.testTag(RoomTags.PLAYBACK_OPTIONS),
        )
        Text(
            stringResource(Res.string.cosmo_room_playback_options_hint),
            style = type.caption,
            color = Color(palette.textTertiary),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
        )
    }
}

/** The seek bar's look without its gestures, for a person who may not move the room's playhead. */
@Composable
private fun ProgressTrack(fraction: Float) {
    val palette = LocalCosmoColors.current.palette
    val trackInk = Color(palette.textPrimary).copy(alpha = 0.16f)
    val accent = Color(palette.accent)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .testTag(RoomTags.SEEK)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        val mid = size.height / 2f
        val stroke = 4.dp.toPx()
        val cap = stroke / 2f
        drawLine(trackInk, Offset(cap, mid), Offset(size.width - cap, mid), stroke, StrokeCap.Round)
        drawLine(accent, Offset(cap, mid), Offset(cap + (size.width - stroke) * fraction, mid), stroke, StrokeCap.Round)
    }
}

@Composable
private fun Transport(
    isPlaying: Boolean,
    canControl: Boolean,
    actions: RoomActions,
    showPrevious: Boolean = true,
    showNext: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A missing skip keeps its place as empty space, so play and pause stay in the middle.
            if (showPrevious) {
                CosmoIconButton(
                    CosmoIcons.Previous, stringResource(Res.string.widget_previous), actions::previous,
                    size = 56.dp, enabled = canControl, modifier = Modifier.testTag(RoomTags.PREVIOUS),
                )
            } else {
                Spacer(Modifier.size(56.dp))
            }
            PlayMorphButton(
                playing = isPlaying,
                loading = false,
                onClick = { if (isPlaying) actions.pause() else actions.play() },
                size = 72.dp,
                enabled = canControl,
                modifier = Modifier.testTag(RoomTags.PLAY_PAUSE),
            )
            if (showNext) {
                CosmoIconButton(
                    CosmoIcons.Next, stringResource(Res.string.widget_next), actions::next,
                    size = 56.dp, enabled = canControl, modifier = Modifier.testTag(RoomTags.NEXT),
                )
            } else {
                Spacer(Modifier.size(56.dp))
            }
        }
        if (!canControl) HostOnlyHint(Modifier.padding(top = 4.dp))
    }
}

/** Why a control is greyed out: the host has kept the music to themselves. */
@Composable
internal fun HostOnlyHint(modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        stringResource(Res.string.cosmo_room_host_only_hint),
        style = type.meta,
        color = Color(palette.textSecondary),
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = CosmoSpacing.margin).testTag(RoomTags.HOST_ONLY_HINT),
    )
}

@Composable
private fun QueueHeader(showClear: Boolean, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = 8.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.cosmo_room_queue),
            style = type.titleMedium,
            color = Color(palette.textPrimary),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showClear) {
            val clearLabel = stringResource(Res.string.cosmo_room_queue_clear_label)
            KitTextButton(
                stringResource(Res.string.cosmo_room_queue_clear),
                actions::clearQueue,
                modifier = Modifier.testTag(RoomTags.QUEUE_CLEAR).semantics { contentDescription = clearLabel },
            )
        }
    }
}

/**
 * One song of the shared queue. The playing one sits on `surface` with its title in the accent; tapping another plays it now in the
 * room. Without control the rows are only a list to read.
 */
@Composable
private fun QueueRow(track: PartyTrack, index: Int, current: Boolean, canControl: Boolean, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val playLabel = stringResource(Res.string.cosmo_room_play_now)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .then(if (current) Modifier.background(Color(palette.surface)) else Modifier)
            .clickable(enabled = canControl && !current, role = Role.Button, onClickLabel = playLabel) { actions.playTrack(track) }
            .semantics { if (current) selected = true }
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag(RoomTags.queueRow(index)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitArtwork(track.thumbnailUrl, KitArt.SQUARE, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title,
                style = type.forText(type.rowTitle, track.title),
                color = Color(if (current) palette.accentStrong else palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist.isNotBlank()) {
                Text(track.artist, style = type.forText(type.meta, track.artist), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (current) {
            Icon(
                CosmoIcons.Waveform,
                contentDescription = stringResource(Res.string.cosmo_room_now_playing_label),
                tint = Color(palette.accentStrong),
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
            )
        } else if (canControl) {
            CosmoIconButton(
                CosmoIcons.Close,
                stringResource(Res.string.cosmo_room_queue_remove, track.title),
                { actions.removeFromQueue(track.videoId) },
                size = 40.dp,
                tint = Color(palette.textSecondary),
                modifier = Modifier.testTag(RoomTags.queueRemove(index)),
            )
        }
    }
}
