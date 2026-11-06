package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.PlayerSettings
import com.cosmotune.music.ui.player.QueueDrag
import com.cosmotune.music.ui.player.QueueSection
import com.cosmotune.music.ui.player.QueueTrack
import com.cosmotune.music.ui.player.rememberQueueDrag
import com.cosmotune.music.ui.player.rememberRemoteArtworkUrl
import com.cosmotune.music.ui.player.splitQueue
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRowTime
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitSongRow
import kotlin.math.roundToInt

/**
 * Up next (board section 1): the queue as Home's song rows, with round covers and the playing row in peach wearing its orbit.
 * Everything the old queue could do is here: a tap plays a row, the handle at a row's start drags it within its own group
 * (what you queued, what comes from where you started, what AutoPlay picked), the cross removes it, Clear empties what you
 * queued, and AutoPlay can be turned on and off at the head of its group. The songs already played show above when the setting
 * for it is on. In a party whose host holds the controls ([controlsLocked]) the queue is only looked at.
 */
@Composable
internal fun NowPlayingUpNext(
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    isPlaying: Boolean,
    /** Where the playing song is (0..1); null where it is not known, and the playing row's moon circles while it plays. */
    progress: (() -> Float)?,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    /** Null where the host has no way to turn AutoPlay on or off (the desktop's side panel): the group heads show no button. */
    onToggleAutoplay: (() -> Unit)?,
    onDragActiveChange: (Boolean) -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    header: Boolean = true,
) {
    val listState = rememberLazyListState()
    val showHistory by PlayerSettings.showQueueHistory.collectAsStateWithLifecycle()
    val tracks = remember(queue, queueIndex, showHistory) { splitQueue(queue, queueIndex, showHistory) }
    val drag = rememberQueueDrag(listState, tracks, onMove, onDragActiveChange)
    val nowPlaying = tracks.nowPlaying
    // The playing row in view when the song changes; the history stays above it, a scroll away.
    LaunchedEffect(queueIndex, nowPlaying?.key, showHistory) {
        if (drag.held == null && nowPlaying != null) listState.scrollToItem(if (tracks.history.isEmpty()) 0 else tracks.history.size + 1)
    }
    val contextSong = tracks.context.firstOrNull()?.song
    val contextTitle = contextSong?.playbackSource?.takeIf { it.isNotBlank() } ?: contextSong?.albumName?.takeIf { it.isNotBlank() }
    Column(modifier) {
        if (header) {
            OrbitSectionHeader(
                "Up next", if (autoplayEnabled) "AutoPlay keeps going with similar songs" else "AutoPlay is off", fonts, look, Modifier,
            )
            Spacer(Modifier.height(14.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (tracks.history.isNotEmpty()) {
                    item(key = "h:history") { GroupHead("Played before", fonts, look) }
                    items(tracks.history, key = { it.key }) { track ->
                        TrackRow(track, fonts, look, onClick = { onJumpTo(track.timelineIndex) }.takeUnless { controlsLocked }, modifier = placed())
                    }
                }
                if (nowPlaying != null) {
                    item(key = nowPlaying.key) {
                        TrackRow(nowPlaying, fonts, look, onClick = null, now = true, playing = isPlaying, progress = progress?.let { p -> { p() } }, modifier = placed())
                    }
                }
                if (tracks.user.isNotEmpty()) {
                    item(key = "h:user") {
                        GroupHead("Next in queue", fonts, look, placed()) {
                            GroupAction("Clear", fonts, look, enabled = !controlsLocked, onClear)
                        }
                    }
                    group(tracks.user, QueueSection.USER, drag, controlsLocked, fonts, look, onJumpTo, onRemove)
                }
                if (tracks.context.isNotEmpty()) {
                    item(key = "h:context") { GroupHead(contextTitle?.let { "Next from $it" } ?: "Next", fonts, look, placed()) }
                    group(tracks.context, QueueSection.CONTEXT, drag, controlsLocked, fonts, look, onJumpTo, onRemove)
                }
                if (autoplayEnabled || tracks.autoplay.isNotEmpty()) {
                    item(key = "h:autoplay") {
                        GroupHead("AutoPlay", fonts, look, placed()) {
                            if (onToggleAutoplay != null) GroupAction(if (autoplayEnabled) "Turn off" else "Turn on", fonts, look, enabled = !controlsLocked, onToggleAutoplay)
                        }
                    }
                    group(tracks.autoplay, QueueSection.AUTOPLAY, drag, controlsLocked, fonts, look, onJumpTo, onRemove)
                } else {
                    item(key = "h:autoplay-off") {
                        GroupHead("AutoPlay is off", fonts, look, placed()) {
                            if (onToggleAutoplay != null) GroupAction("Turn on", fonts, look, enabled = !controlsLocked, onToggleAutoplay)
                        }
                    }
                }
            }
            // The held row, drawn where the finger is (see QueueDrag): nothing the list does under it moves it.
            drag.held?.let { held ->
                Box(Modifier.matchParentSize()) {
                    TrackRow(held, fonts, look, onClick = null, handle = Modifier, modifier = Modifier.offset { IntOffset(0, drag.heldTop.roundToInt()) })
                }
            }
        }
    }
}

private val ROW_MOTION = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)

private fun LazyItemScope.placed(): Modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = ROW_MOTION)

/** One group's rows; the dragged row stays in the list unseen, trading places, while its copy is drawn over the list. */
private fun LazyListScope.group(
    rows: List<QueueTrack>,
    section: QueueSection,
    drag: QueueDrag,
    locked: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    items(rows, key = { it.key }) { track ->
        val held = drag.held?.key == track.key
        if (held) DisposableEffect(Unit) { onDispose { drag.end(track.key) } }
        TrackRow(
            track, fonts, look,
            onClick = { onJumpTo(track.timelineIndex) }.takeUnless { locked },
            onRemove = { onRemove(track.timelineIndex) }.takeUnless { locked },
            handle = if (locked) null else Modifier.pointerInput(track.key) {
                detectDragGestures(
                    onDragStart = { drag.start(track.key, section) },
                    onDragEnd = { drag.end(track.key) },
                    onDragCancel = { drag.end(track.key) },
                ) { change, amount -> change.consume(); drag.drag(amount.y) }
            },
            modifier = if (held) placed().alpha(0f) else placed(),
        )
    }
}

@Composable
private fun TrackRow(
    track: QueueTrack,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    playing: Boolean = false,
    progress: (() -> Float?)? = null,
    onRemove: (() -> Unit)? = null,
    handle: Modifier? = null,
) {
    val song = track.song
    OrbitSongRow(
        song.title, song.artist, rememberRemoteArtworkUrl(song), fonts, look, onClick = onClick,
        modifier = modifier.fillMaxWidth(), now = now, playing = playing, progress = progress, height = 64.dp,
        leading = handle?.let { h ->
            {
                Box(h.size(28.dp, 48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = look.dim, modifier = Modifier.size(18.dp))
                }
            }
        },
    ) {
        if (onRemove != null) {
            OrbitRowTime(song.durationText, fonts, look)
            Box(Modifier.padding(start = 6.dp).size(32.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(CosmoIcons.Close, "Remove from the queue", tint = look.dim, modifier = Modifier.size(16.dp))
            }
        } else if (!now) {
            OrbitRowTime(song.durationText, fonts, look)
        }
    }
}

@Composable
private fun GroupHead(title: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = fonts.label, color = look.dim, maxLines = 1, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun GroupAction(title: String, fonts: OrbitFonts, look: OrbitLook, enabled: Boolean, onClick: () -> Unit) {
    Text(
        title, style = fonts.chip.copy(fontSize = 13.sp), color = if (enabled) look.accent else look.dim,
        modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick).background(look.chip).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
