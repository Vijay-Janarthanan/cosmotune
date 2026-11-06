package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPersonDisc
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import org.jetbrains.compose.resources.stringResource

/**
 * Now Playing in the room (board batch-a, section 3): Home's colour card in the playing cover's own colour, the mono "PLAYING FOR
 * EVERYONE · 1:52 / 4:05", the title, who started it, the disc in its orbit, the shared playhead, and play in the bite, the bite and
 * the transport only for a person who may control (the host can keep the music to themselves, and the card then says so under it).
 * Under the card, the people as discs (a tap goes to People), then Up next as the kit's song rows, with "Ask for a song".
 *
 * The same page plays every kind of item: a video shows the platform's [media] surface (or its thumbnail) at 16:9 above the card, so
 * nothing sits on top of it (rule 9); a live station has no playhead and says so, and a video or a podcast offers this device's own
 * playback options.
 */
@Composable
internal fun RoomNowPlayingPage(
    state: RoomUiState,
    room: RoomState,
    actions: RoomActions,
    wide: Boolean,
    media: (@Composable () -> Unit)? = null,
    onPeople: () -> Unit = {},
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val track = room.playback.track
    val queue = room.queue.items
    val canControl = state.canControl
    // A kind from a newer app is kept as it is: it is not drawn as a song, and its titles say to update.
    val kind = track?.kind ?: PartyTrack.KIND_MUSIC
    val live = track?.isLive == true
    val side = if (wide) 0.dp else roomSide(false)
    LazyColumn(
        Modifier.fillMaxSize().testTag(RoomTags.PAGE_NOW_PLAYING),
        contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (track == null) {
            item(key = "empty") { NothingPlaying(canControl, actions, fonts, look) }
        } else {
            if (kind == PartyTrack.KIND_VIDEO || kind == PartyTrack.KIND_WATCH) item(key = "video") { VideoFrame(track, media, look) }
            item(key = "card") {
                val index = room.queue.index
                NowPlayingCard(
                    track = track,
                    kind = kind,
                    isPlaying = room.playback.isPlaying,
                    positionMs = state.positionMs,
                    startedBy = roomStartedByName(room.playback.startedByName, room.playback.startedBy, room.members),
                    canControl = canControl,
                    // A station has a previous and a next only when the shared queue has one either side of it.
                    showPrevious = !live || index > 0,
                    showNext = !live || (index >= 0 && index < queue.lastIndex),
                    wide = wide,
                    actions = actions,
                    fonts = fonts,
                    look = look,
                )
            }
            if (!canControl) item(key = "host-only") { HostOnlyHint(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) }
            // Quality, captions and speed are each person's own, so this is open to everyone, host-only room or not.
            if (kind == PartyTrack.KIND_VIDEO || kind == PartyTrack.KIND_PODCAST || kind == PartyTrack.KIND_WATCH) {
                item(key = "options") { PlaybackOptions(actions, fonts, look) }
            }
        }
        item(key = "people") { RoomPeopleStrip(state, room, onPeople, fonts, look, Modifier.padding(top = 12.dp)) }
        item(key = "queue-head") { QueueHeader(canControl && queue.isNotEmpty(), actions, fonts, look) }
        if (queue.isEmpty()) {
            item(key = "queue-empty") {
                Text(stringResource(Res.string.cosmo_room_queue_empty), style = fonts.body, color = look.dim, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
        // A queue may hold the same song twice, so the key is its place as well as its id.
        itemsIndexed(queue, key = { index, item -> "q:$index:${item.videoId}" }) { index, item ->
            QueueRow(item, index, current = index == room.queue.index, playing = room.playback.isPlaying, canControl = canControl, actions = actions, fonts = fonts, look = look)
        }
    }
}

/** Nothing plays yet: a quiet card that says so, and "Ask for a song". */
@Composable
private fun NothingPlaying(canControl: Boolean, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(look.row).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NOTHING PLAYING", style = fonts.label, color = look.dim)
        Text(stringResource(Res.string.cosmo_room_nothing_playing), style = fonts.section, color = look.ink, modifier = Modifier.semantics { heading() })
        if (!canControl) HostOnlyHint()
        AskChip(actions, fonts, look)
    }
}

/** "Ask for a song": the chip that opens the platform's song picker; what is picked arrives in the chat as a request. */
@Composable
internal fun AskChip(actions: RoomActions, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    OrbitChip(
        "Ask for a song", fonts, look, actions::askToPlay,
        modifier = modifier.testTag(RoomTags.ASK_TO_PLAY),
        leading = { Icon(CosmoIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

/**
 * A video at 16:9, rounded (rule 1: video stays rectangular): the platform's own surface from [media] when it gave one (each device
 * plays the video itself), else the thumbnail on the quiet fill.
 */
@Composable
private fun VideoFrame(track: PartyTrack, media: (@Composable () -> Unit)?, look: OrbitLook) {
    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp))
            .background(if (media != null) Color.Black else look.row)
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

/**
 * The room's colour card: the cover's deep colour ([coverFill]) with its pale ink; the mono line, the title, the artist, what kind of
 * item it is (music says nothing), who started it, previous and next, the playhead (read-only without control or without a length,
 * a live note on a station), and the disc in its orbit at the top end (not for a video, whose picture is above the card). With
 * control the card has the bite and play stands in it; without it the card is whole.
 */
@Composable
private fun NowPlayingCard(
    track: PartyTrack,
    kind: String,
    isPlaying: Boolean,
    positionMs: Long,
    startedBy: String?,
    canControl: Boolean,
    showPrevious: Boolean,
    showNext: Boolean,
    wide: Boolean,
    actions: RoomActions,
    fonts: OrbitFonts,
    look: OrbitLook,
) {
    val (fill, ink) = coverFill(track.thumbnailUrl, track.videoId)
    val durationMs = track.durationMs
    val live = track.isLive
    val content: @Composable BoxScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = if (!canControl) 184.dp else if (wide) 230.dp else 232.dp)) {
            val roomy = maxWidth >= 520.dp
            val disc = if (kind == PartyTrack.KIND_VIDEO) 0.dp else if (roomy) 150.dp else (maxWidth * 0.26f).coerceIn(72.dp, 104.dp)
            // On a roomy card the disc stands clear of the bite to its left (the board's); on a phone it sits in the top corner, above it.
            val discEnd = if (roomy) 112.dp else 18.dp
            val textEnd = if (disc > 0.dp) disc + 12.dp + discEnd + 14.dp else 24.dp
            if (disc > 0.dp) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = discEnd - 6.dp).size(disc + 12.dp), contentAlignment = Alignment.Center) {
                    Disc(track.thumbnailUrl, disc)
                    OrbitRing(
                        disc + 12.dp, ink,
                        progress = { if (live || durationMs == null || durationMs <= 0L) null else roomPositionFraction(positionMs, durationMs) },
                        spinning = isPlaying, stroke = 2.dp, moon = 4.dp,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 22.dp, end = textEnd, bottom = 58.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) { LiveBadge(fonts, ink); Spacer(Modifier.width(8.dp)) }
                    val clockLine = if (live || durationMs == null || durationMs <= 0L) "" else " · ${formatRoomClock(positionMs)} / ${formatRoomClock(durationMs)}"
                    Text((if (roomy) "PLAYING FOR EVERYONE" else "FOR EVERYONE") + clockLine, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    track.title,
                    style = if (roomy) fonts.heroTitle.copy(fontSize = 30.sp, lineHeight = 32.sp) else fonts.heroTitle.copy(fontSize = 24.sp, lineHeight = 26.sp),
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).semantics { heading() },
                )
                if (track.artist.isNotBlank()) {
                    Text(track.artist, style = fonts.body.copy(fontSize = 14.sp), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                // Music needs no word for what it is; the other kinds say so, since the same card plays all of them.
                val kindLabel = when (kind) {
                    PartyTrack.KIND_VIDEO -> Res.string.cosmo_room_kind_video
                    PartyTrack.KIND_RADIO -> Res.string.cosmo_room_kind_radio
                    PartyTrack.KIND_PODCAST -> Res.string.cosmo_room_kind_podcast
                    PartyTrack.KIND_WATCH -> Res.string.cosmo_room_kind_watch
                    else -> if (track.needsUpdate) Res.string.cosmo_room_needs_update else null
                }
                if (kindLabel != null) {
                    Text(stringResource(kindLabel), style = fonts.body.copy(fontSize = 12.5.sp), color = ink.copy(alpha = 0.75f), maxLines = 1, modifier = Modifier.padding(top = 2.dp).testTag(RoomTags.KIND))
                }
                if (startedBy != null) {
                    Text(stringResource(Res.string.cosmo_room_started_by, startedBy), style = fonts.body.copy(fontSize = 12.5.sp), color = ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                if (canControl && (showPrevious || showNext)) {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showPrevious) OrbitRoundButton(CosmoIcons.Previous, stringResource(Res.string.widget_previous), ink, actions::previous, Modifier.testTag(RoomTags.PREVIOUS))
                        if (showNext) OrbitRoundButton(CosmoIcons.Next, stringResource(Res.string.widget_next), ink, actions::next, Modifier.testTag(RoomTags.NEXT))
                    }
                }
            }
            // The line stops short of the bite when there is one.
            val lineModifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 24.dp, end = if (canControl) 108.dp else 24.dp, bottom = 22.dp)
            if (live) {
                Text(stringResource(Res.string.cosmo_room_live_note), style = fonts.body, color = ink.copy(alpha = 0.85f), modifier = lineModifier.testTag(RoomTags.LIVE_NOTE))
            } else {
                val fraction = roomPositionFraction(positionMs, durationMs)
                val seekable = canControl && durationMs != null && durationMs > 0L
                OrbitProgressLine(
                    progress = { roomPositionFraction(positionMs, durationMs) },
                    ink = ink,
                    modifier = lineModifier.testTag(RoomTags.SEEK).then(if (seekable) Modifier else Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }),
                    onSeek = if (seekable) { at -> actions.seekTo(roomSeekTarget(at, durationMs!!)) } else null,
                    moon = 5.dp,
                )
            }
        }
    }
    if (canControl) {
        OrbitBiteCard(
            fill,
            Modifier.fillMaxWidth(),
            bite = { OrbitPlayButton(isPlaying, { if (isPlaying) actions.pause() else actions.play() }, look, Modifier.testTag(RoomTags.PLAY_PAUSE)) },
            content = content,
        )
    } else {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(fill), content = content)
    }
}

/** The "LIVE" a station carries on its card: a red dot and the word, in the card's ink. */
@Composable
private fun LiveBadge(fonts: OrbitFonts, ink: Color) {
    Row(
        Modifier.clip(CircleShape).background(ink.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp).testTag(RoomTags.LIVE_BADGE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE5484D)))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.cosmo_room_live_badge), style = fonts.label, color = ink, maxLines = 1)
    }
}

/** This device's own quality, captions and speed: a chip under the card, with a line that says it changes nobody else's. */
@Composable
private fun PlaybackOptions(actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitChip(
            stringResource(Res.string.cosmo_room_playback_options), fonts, look, actions::openPlaybackOptions,
            modifier = Modifier.testTag(RoomTags.PLAYBACK_OPTIONS),
            leading = { Icon(CosmoIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(Res.string.cosmo_room_playback_options_hint), style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.weight(1f))
    }
}

/** Why there is no play button: the host has kept the music to themselves. */
@Composable
internal fun HostOnlyHint(modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Text(
        stringResource(Res.string.cosmo_room_host_only_hint),
        style = fonts.body,
        color = look.dim,
        modifier = modifier.testTag(RoomTags.HOST_ONLY_HINT),
    )
}

/**
 * The people as the board draws them: a disc each ([RoomAvatar], ringed green while they speak) with the name and a mono status
 * under it, host first and you after; one button to People, named with everyone for a screen reader.
 */
@Composable
internal fun RoomPeopleStrip(state: RoomUiState, room: RoomState, onPeople: () -> Unit, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val members = sortRoomMembers(room.members, state.selfUid)
    val label = stringResource(Res.string.cosmo_room_people_strip, members.joinToString(", ") { it.name })
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.cosmo_room_tab_people), style = fonts.section, color = look.ink, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())
                .semantics { contentDescription = label }
                .testTag(RoomTags.AVATARS),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (member in members) {
                val self = member.uid == state.selfUid
                val talking = state.showsSpeaking(member.uid)
                OrbitPersonDisc(
                    name = if (self) "You" else member.name,
                    status = personStatus(member, talking, member.uid in state.mutedHere),
                    talking = talking,
                    fonts = fonts,
                    look = look,
                    onClick = onPeople,
                ) { RoomAvatar(member.avatarUrl, PERSON_DP, member.uid, talking, name = member.name) }
            }
        }
    }
}

/** What a person is doing, in the order it matters: talking, muted here, away, their mic on, host, or just listening. */
private fun personStatus(member: RoomMember, talking: Boolean, mutedHere: Boolean): String = when {
    talking -> "Talking"
    mutedHere -> "Muted"
    !member.connected -> "Away"
    member.micLive -> "Mic on"
    member.isHost -> "Host"
    else -> "Listening"
}

@Composable
private fun QueueHeader(showClear: Boolean, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.cosmo_room_queue), style = fonts.section, color = look.ink, modifier = Modifier.weight(1f).semantics { heading() })
        if (showClear) {
            val clearLabel = stringResource(Res.string.cosmo_room_queue_clear_label)
            OrbitChip(
                stringResource(Res.string.cosmo_room_queue_clear), fonts, look, actions::clearQueue,
                modifier = Modifier.testTag(RoomTags.QUEUE_CLEAR).semantics { contentDescription = clearLabel },
                leading = null,
            )
            Spacer(Modifier.width(8.dp))
        }
        AskChip(actions, fonts, look)
    }
}

/**
 * One song of the shared queue as the kit's song row: the playing one has the peach wash and its disc wears the orbit; tapping
 * another plays it now in the room, and its round button takes it out. Without control the rows are only a list to read.
 */
@Composable
private fun QueueRow(track: PartyTrack, index: Int, current: Boolean, playing: Boolean, canControl: Boolean, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    OrbitSongRow(
        title = track.title,
        subtitle = track.artist,
        cover = track.thumbnailUrl,
        fonts = fonts,
        look = look,
        onClick = if (canControl && !current) ({ actions.playTrack(track) }) else null,
        modifier = Modifier.fillMaxWidth().testTag(RoomTags.queueRow(index)).semantics { if (current) selected = true },
        now = current,
        playing = playing && current,
        height = 64.dp,
        endPadding = if (canControl && !current) 8.dp else 16.dp,
        trailingText = track.durationMs?.takeIf { it > 0L }?.let(::formatRoomClock),
        trailing = if (canControl && !current) {
            {
                Spacer(Modifier.width(6.dp))
                OrbitRoundButton(
                    CosmoIcons.Close,
                    stringResource(Res.string.cosmo_room_queue_remove, track.title),
                    look.ink,
                    { actions.removeFromQueue(track.videoId) },
                    Modifier.testTag(RoomTags.queueRemove(index)),
                    size = 36.dp,
                )
            }
        } else null,
    )
}

private const val PERSON_DP = 72
