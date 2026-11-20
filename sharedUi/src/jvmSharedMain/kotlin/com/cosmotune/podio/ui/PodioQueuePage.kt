package com.cosmotune.podio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.player.QueueDrag
import com.cosmotune.music.ui.player.QueueSection
import com.cosmotune.music.ui.player.QueueTrack
import com.cosmotune.music.ui.player.QueueTracks
import com.cosmotune.music.ui.player.rememberQueueDrag
import com.cosmotune.podio.PodioQueueEntry
import com.cosmotune.podio.PodioQueueState
import com.cosmotune.podio.item
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*
import kotlin.math.roundToInt

/** The queue as Music's queue list takes it: the playing episode, then Play next (its user group) and Up next (its context group). */
internal fun podioQueueTracks(state: PodioQueueState): QueueTracks {
    fun track(index: Int, entry: PodioQueueEntry) = QueueTrack(index, entry.episode.item().copy(artwork = entry.episode.artwork.ifBlank { entry.show.artwork }).song(), "q_" + entry.id)
    val tracks = state.entries.mapIndexed(::track)
    return QueueTracks(
        nowPlaying = state.current?.let { QueueTrack(-1, it.episode.item().copy(artwork = it.episode.artwork.ifBlank { it.show.artwork }).song(), "now_" + it.id) },
        user = tracks.filterIndexed { index, _ -> state.entries[index].playNext },
        context = tracks.filterIndexed { index, _ -> !state.entries[index].playNext },
        autoplay = emptyList(),
    )
}

/** "4 episodes · 2 h 6 min" (what is queued, and how long it runs together). */
internal fun queueLabel(state: PodioQueueState): String {
    val count = state.entries.size
    return PodioFormat.episodeMeta(if (count == 1) "1 episode" else "$count episodes", PodioFormat.duration(state.seconds))
}

/**
 * Up next (board frame 4), over the section: the playing episode in peach; "Play next" (what a swipe to the right or the episode's
 * Play next put first) with Clear; "Up next" with Shuffle; then "When the queue ends" with Keep going. The rows are Music's queue
 * rows: the handle at the start drags a row within its group, the cross at the end (or a swipe to the left) takes it out, with
 * Undo; a tap plays it.
 */
@Composable internal fun PodioQueuePage(m: PodioModel, env: PodioEnv, onBack: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val state = env.queue
    val list = rememberLazyListState()
    val tracks = remember(state) { podioQueueTracks(state) }
    val drag = rememberQueueDrag(list, tracks, onMove = { from, to -> m.moveInQueue(from, to) }, onActiveChange = {})
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:queue")) {
        val side = orbitPageSide(maxWidth)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
                item(key = "h:back") {
                    PodioBackHead("Podio", onBack, fonts, look, side) {
                        if (state.entries.isNotEmpty()) PodioTextAction("Clear", look, fonts, m::clearQueue, Modifier.testTag("podio:queue:clear"))
                    }
                }
                item(key = "h:heading") {
                    OrbitPageHeading("Up next", queueLabel(state), fonts, look, Modifier.padding(start = side + 4.dp, end = side, top = 22.dp, bottom = 8.dp))
                }
                tracks.nowPlaying?.let { now ->
                    val current = state.current
                    item(key = now.key) {
                        val playing = env.playback.id == current?.id
                        OrbitSongRow(now.song.title, now.song.artist, now.song.thumbnailUrl, fonts, look, onClick = if (playing) env.onToggle else current?.let { { m.playQueued(it) } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 8.dp), now = true, playing = playing && env.playback.playing,
                            progress = if (playing) ({ env.playedFraction() }) else null, height = 64.dp,
                            trailing = { if (playing) OrbitPlayButton(env.playback.playing, env.onToggle, look, size = 38.dp) })
                    }
                }
                if (state.entries.isEmpty()) item(key = "h:empty") {
                    PodioMessage("Up next", "Nothing queued", "Swipe an episode to the right, or choose Play next or Add to queue in its sheet, to line it up here.", fonts, look, side)
                }
                if (tracks.user.isNotEmpty()) {
                    item(key = "h:user") { GroupHead("Play next", side, fonts, look) { PodioTextAction("Clear", look, fonts, m::clearPlayNext) } }
                    group(tracks.user, QueueSection.USER, drag, side, state, m, env, fonts, look)
                }
                if (tracks.context.isNotEmpty()) {
                    item(key = "h:context") { GroupHead("Up next", side, fonts, look) { if (tracks.context.size > 1) PodioTextAction("Shuffle", look, fonts, m::shuffleQueue) } }
                    group(tracks.context, QueueSection.CONTEXT, drag, side, state, m, env, fonts, look)
                }
                item(key = "h:end") {
                    Column(Modifier.padding(horizontal = side).padding(top = 24.dp)) {
                        OrbitLabel("When the queue ends", fonts, look.dim, Modifier.padding(start = 4.dp, bottom = 8.dp))
                        OrbitSwitchRow("Keep going", state.keepGoing, m::setKeepGoing, detail = "New episodes from shows you follow", switchModifier = Modifier.testTag("podio:queue:keep-going"))
                    }
                }
            }
            // The held row, drawn where the finger is (Music's queue does the same): nothing the list does under it moves it.
            drag.held?.let { held ->
                Box(Modifier.matchParentSize()) {
                    QueueRow(held, fonts, look, onClick = null, onRemove = null, handle = Modifier,
                        modifier = Modifier.padding(horizontal = side).offset { IntOffset(0, drag.heldTop.roundToInt()) })
                }
            }
        }
    }
}

@Composable private fun GroupHead(title: String, side: androidx.compose.ui.unit.Dp, fonts: OrbitFonts, look: OrbitLook, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = side + 4.dp, end = side, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitLabel(title, fonts, look.dim, Modifier.weight(1f))
        action()
    }
}

private fun LazyItemScope.placed(): Modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)

private fun LazyListScope.group(rows: List<QueueTrack>, section: QueueSection, drag: QueueDrag, side: androidx.compose.ui.unit.Dp, state: PodioQueueState,
    m: PodioModel, env: PodioEnv, fonts: OrbitFonts, look: OrbitLook) {
    items(rows, key = { it.key }) { track ->
        val entry = state.entries.getOrNull(track.timelineIndex)
        val held = drag.held?.key == track.key
        if (held) DisposableEffect(Unit) { onDispose { drag.end(track.key) } }
        val remove = entry?.let { { m.removeFromQueue(it.id) } }
        PodioSwipe((if (held) placed().alpha(0f) else placed()).padding(horizontal = side).padding(bottom = 6.dp), endLabel = "Remove", onEnd = remove) {
            QueueRow(track, fonts, look, onClick = entry?.let { { m.playQueued(it) } }, onRemove = remove,
                handle = Modifier.pointerInput(track.key) {
                    detectDragGestures(
                        onDragStart = { drag.start(track.key, section) },
                        onDragEnd = { drag.end(track.key) },
                        onDragCancel = { drag.end(track.key) },
                    ) { change, amount -> change.consume(); drag.drag(amount.y) }
                })
        }
    }
}

/** Music's queue row: the drag handle at the start, the cover, the title over the show, the length and the cross at the end. */
@Composable private fun QueueRow(track: QueueTrack, fonts: OrbitFonts, look: OrbitLook, onClick: (() -> Unit)?, onRemove: (() -> Unit)?, handle: Modifier?,
    modifier: Modifier = Modifier) {
    val song = track.song
    OrbitSongRow(song.title, song.artist, song.thumbnailUrl, fonts, look, onClick = onClick, modifier = modifier.fillMaxWidth(), height = 64.dp,
        leading = handle?.let { h -> {
            Box(h.size(28.dp, 48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = look.dim, modifier = Modifier.size(18.dp)) }
        } }) {
        OrbitRowTime(song.durationText, fonts, look)
        if (onRemove != null) Box(Modifier.padding(start = 6.dp).size(32.dp).clip(CircleShape).clickable(onClick = onRemove).testTag("podio:queue:remove"),
            contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.Close, "Remove from the queue", tint = look.dim, modifier = Modifier.size(16.dp))
        }
    }
}
