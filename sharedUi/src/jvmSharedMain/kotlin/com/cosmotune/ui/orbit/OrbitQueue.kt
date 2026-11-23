package com.cosmotune.ui.orbit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import kotlin.math.roundToInt

/*
 * Up next in Orbit (the Music board's 7a and 7b), one piece for every host that shows the queue: the mono "PLAYING FROM" line over
 * "Up next." and the count and length, one row of chips (Save as playlist, Shuffle, Select, Clear), then the queue as flat rows: the
 * playing one in peach with its orbit, and each coming one with its drag handle as its one trailing button. A row swiped to the left
 * is removed, and "Removed · Undo" ([LocalOrbitUndo]) puts it back. Select (or a hold) turns the discs into checks and the bar at the
 * foot offers Play next, Add to playlist, Download and Remove for the songs chosen.
 */

/**
 * The queue ([queue], the playing one at [queueIndex]). A tap plays a row ([onJumpTo]); the handle drags it within its own group
 * ([onMove]: what you queued, what comes from where you started, what AutoPlay picked); a swipe to the left removes it ([onRemove]),
 * and Undo calls [onRestore] with the song and the place it had (no Undo without it); Clear ([onClear]) empties what you queued.
 * [onToggleAutoplay] (null where the host has none) turns AutoPlay on and off at the head of its group. [onSaveAsPlaylist],
 * [onShuffle], [onPlayNext], [onAddToPlaylist] and [onDownload] add their chip or their action in select mode when given. In a party
 * whose host holds the controls ([controlsLocked]) the queue is only looked at. The songs already played show above while the
 * setting for it is on. [playingFrom] names where the queue started ("Album title").
 */
@Composable
fun OrbitQueueList(
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    isPlaying: Boolean,
    progress: (() -> Float)?,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onToggleAutoplay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDragActiveChange: (Boolean) -> Unit = {},
    playingFrom: String? = null,
    onRestore: ((Song, Int) -> Unit)? = null,
    onSaveAsPlaylist: ((List<Song>) -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onPlayNext: ((List<Song>) -> Unit)? = null,
    onAddToPlaylist: ((List<Song>) -> Unit)? = null,
    onDownload: ((List<Song>) -> Unit)? = null,
    header: Boolean = true,
    /**
     * False where a panel's own head already says "Up next" (the desktop's side panel): the big title goes, and where the queue
     * plays from, how many songs and how long stay as one line.
     */
    title: Boolean = true,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val undo = LocalOrbitUndo.current
    val listState = rememberLazyListState()
    val showHistory by PlayerSettings.showQueueHistory.collectAsStateWithLifecycle()
    val tracks = remember(queue, queueIndex, showHistory) { splitQueue(queue, queueIndex, showHistory) }
    val drag = rememberQueueDrag(listState, tracks, onMove, onDragActiveChange)
    val nowPlaying = tracks.nowPlaying
    var selecting by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    if (controlsLocked && selecting) { selecting = false; chosen = emptySet() }
    val coming = remember(tracks) { tracks.user + tracks.context + tracks.autoplay }
    val chosenTracks = remember(coming, chosen) { coming.filter { it.key in chosen } }
    val remove: (QueueTrack) -> Unit = { track ->
        onRemove(track.timelineIndex)
        if (undo != null && onRestore != null) undo.removed(track.song.title) { onRestore(track.song, track.timelineIndex) }
    }
    // The playing row in view when the song changes; the history stays above it, a scroll away.
    LaunchedEffect(queueIndex, nowPlaying?.key, showHistory) {
        if (drag.held == null && nowPlaying != null) listState.scrollToItem(if (tracks.history.isEmpty()) 0 else tracks.history.size + 1)
    }
    val upcoming = coming.size
    val minutes = remember(coming) { coming.sumOf { it.song.durationText.seconds() } / 60 }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                OrbitSelectionHead(chosen.size, onClose = { selecting = false; chosen = emptySet() }, onSelectAll = { chosen = coming.map { it.key }.toSet() }, modifier = Modifier.padding(horizontal = 4.dp))
            } else if (header) {
                if (!title) {
                    val line = listOfNotNull(playingFrom?.takeIf { it.isNotBlank() }?.let { "Playing from $it" }, "$upcoming songs", minutes.takeIf { it > 0 }?.let { "$it min" })
                    Text(line.joinToString(" · "), style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))
                } else Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)) {
                    if (!playingFrom.isNullOrBlank()) OrbitLabel("Playing from · $playingFrom", fonts, look.dim)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            buildAnnotatedString { append("Up next"); withStyle(SpanStyle(color = look.accent)) { append(".") } },
                            style = fonts.heroTitle.copy(fontSize = 26.sp, lineHeight = 30.sp), color = look.ink, modifier = Modifier.weight(1f),
                        )
                        OrbitLabel(listOfNotNull("$upcoming songs", minutes.takeIf { it > 0 }?.let { "$it min" }).joinToString(" · "), fonts, look.dim, Modifier.padding(bottom = 6.dp))
                    }
                }
                if (!controlsLocked) {
                    OrbitChipRow(0.dp, Modifier.padding(bottom = 8.dp)) {
                        if (onSaveAsPlaylist != null && upcoming > 0) OrbitSelectChip("Save as playlist", false, fonts, look, { onSaveAsPlaylist(listOfNotNull(nowPlaying?.song) + coming.map { it.song }) }, icon = CosmoIcons.Plus)
                        if (onShuffle != null) OrbitSelectChip("Shuffle", false, fonts, look, onShuffle, icon = CosmoIcons.Shuffle)
                        if (upcoming > 0) OrbitSelectChip("Select", false, fonts, look, { selecting = true; chosen = emptySet() }, icon = CosmoIcons.Check)
                        if (tracks.user.isNotEmpty()) OrbitSelectChip("Clear", false, fonts, look, onClear, icon = CosmoIcons.Close)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState, contentPadding = PaddingValues(bottom = if (selecting) 96.dp else 16.dp), modifier = Modifier.testTag("orbit:queue"),
                    // Rows stand apart, so a lit row's card or a swiped row's colour never runs into the next.
                    verticalArrangement = Arrangement.spacedBy(QUEUE_ROW_GAP),
                ) {
                    if (tracks.history.isNotEmpty()) {
                        item(key = "h:history") { GroupHead("Played before", fonts, look) }
                        items(tracks.history, key = { it.key }) { track ->
                            QueueRow(track, fonts, look, onClick = { onJumpTo(track.timelineIndex) }.takeUnless { controlsLocked }, modifier = placed())
                        }
                    }
                    if (nowPlaying != null) {
                        item(key = "h:now") { GroupHead("Now playing", fonts, look, placed()) }
                        item(key = nowPlaying.key) {
                            QueueRow(nowPlaying, fonts, look, onClick = null, now = true, playing = isPlaying, progress = progress?.let { p -> { p() } }, modifier = placed())
                        }
                    }
                    val select = { track: QueueTrack -> chosen = if (track.key in chosen) chosen - track.key else chosen + track.key }
                    val groups = listOf(
                        Triple(QueueSection.USER, tracks.user, "Next in queue"),
                        Triple(QueueSection.CONTEXT, tracks.context, if (selecting) "Next" else "Next · hold the handle to move"),
                        Triple(QueueSection.AUTOPLAY, tracks.autoplay, "AutoPlay"),
                    )
                    for ((section, rows, title) in groups) {
                        if (section == QueueSection.AUTOPLAY) {
                            item(key = "h:autoplay") {
                                GroupHead(if (autoplayEnabled || rows.isNotEmpty()) title else "AutoPlay is off", fonts, look, placed()) {
                                    if (onToggleAutoplay != null && !selecting) GroupAction(if (autoplayEnabled) "Turn off" else "Turn on", fonts, look, enabled = !controlsLocked, onToggleAutoplay)
                                }
                            }
                        } else if (rows.isNotEmpty()) {
                            item(key = "h:${section.name}") { GroupHead(title, fonts, look, placed()) }
                        }
                        group(rows, section, drag, controlsLocked, selecting, chosen, fonts, look, onJumpTo, remove, select)
                    }
                }
                // The held row, drawn where the finger is (see QueueDrag): nothing the list does under it moves it.
                drag.held?.let { held ->
                    Box(Modifier.matchParentSize()) {
                        QueueRow(
                            held, fonts, look, onClick = null, handle = Modifier,
                            // The lifted row floats: a soft shadow under a rounded card of the menu's ground.
                            modifier = Modifier.offset { IntOffset(0, drag.heldTop.roundToInt()) }
                                .shadow(12.dp, RoundedCornerShape(16.dp), clip = false).clip(RoundedCornerShape(16.dp)).background(orbitFloatGround(look)),
                        )
                    }
                }
            }
        }
        if (selecting) {
            val songs = chosenTracks.map { it.song }
            OrbitSelectionBar(
                listOfNotNull(
                    onPlayNext?.let { OrbitBarAction(CosmoIcons.Queue, "Play next", enabled = songs.isNotEmpty()) { it(songs); selecting = false; chosen = emptySet() } },
                    onAddToPlaylist?.let { OrbitBarAction(CosmoIcons.Plus, "Add to playlist", enabled = songs.isNotEmpty()) { it(songs) } },
                    onDownload?.let { OrbitBarAction(CosmoIcons.Download, "Download", enabled = songs.isNotEmpty()) { it(songs) } },
                    OrbitBarAction(CosmoIcons.Close, "Remove", enabled = songs.isNotEmpty()) {
                        // From the last place back, so each removal leaves the places before it as they were; one Undo puts all back.
                        val gone = chosenTracks.sortedByDescending { it.timelineIndex }
                        gone.forEach { onRemove(it.timelineIndex) }
                        if (undo != null && onRestore != null) {
                            undo.show(if (gone.size == 1) "Removed “${gone.first().song.title}”" else "Removed ${gone.size} songs") {
                                gone.sortedBy { it.timelineIndex }.forEach { onRestore(it.song, it.timelineIndex) }
                            }
                        }
                        selecting = false; chosen = emptySet()
                    },
                ),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }
}

private fun String?.seconds(): Long {
    val parts = this?.split(':')?.mapNotNull { it.trim().toLongOrNull() } ?: return 0
    return parts.fold(0L) { acc, n -> acc * 60 + n }
}

private val ROW_MOTION = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)

private fun LazyItemScope.placed(): Modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = ROW_MOTION)

/** One group's rows; the dragged row stays in the list unseen, trading places, while its copy is drawn over the list. */
private fun LazyListScope.group(
    rows: List<QueueTrack>,
    section: QueueSection,
    drag: QueueDrag,
    locked: Boolean,
    selecting: Boolean,
    chosen: Set<String>,
    fonts: OrbitFonts,
    look: OrbitLook,
    onJumpTo: (Int) -> Unit,
    onRemove: (QueueTrack) -> Unit,
    onSelect: (QueueTrack) -> Unit,
) {
    items(rows, key = { it.key }) { track ->
        val held = drag.held?.key == track.key
        if (held) DisposableEffect(Unit) { onDispose { drag.end(track.key) } }
        val row: @Composable (Modifier) -> Unit = { m ->
            QueueRow(
                track, fonts, look,
                onClick = when {
                    selecting -> ({ onSelect(track) })
                    locked -> null
                    else -> ({ onJumpTo(track.timelineIndex) })
                },
                onLongClick = if (locked || selecting) null else ({ onSelect(track) }),
                selected = if (selecting) track.key in chosen else null,
                handle = if (locked || selecting) null else Modifier.pointerInput(track.key) {
                    detectDragGestures(
                        onDragStart = { drag.start(track.key, section) },
                        onDragEnd = { drag.end(track.key) },
                        onDragCancel = { drag.end(track.key) },
                    ) { change, amount -> change.consume(); drag.drag(amount.y) }
                },
                modifier = m,
                // The desktop's pointer removes with a button on the row (shown under the pointer); a finger swipes the row away.
                onRemove = if (orbitIsDesktop && !locked && !selecting) ({ onRemove(track) }) else null,
            )
        }
        val m = if (held) placed().alpha(0f) else placed()
        if (locked || selecting || orbitIsDesktop) row(m) else SwipeToRemove(onRemove = { onRemove(track) }, look = look, fonts = fonts, modifier = m) { row(Modifier) }
    }
}

/** A row that a swipe to the left removes: what the swipe uncovers is the danger colour with "Remove". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, look: OrbitLook, fonts: OrbitFonts, modifier: Modifier, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(onRemove)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) current()
            false
        },
        positionalThreshold = { it * 0.4f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Drawn only in the strip the row has left (the row itself is see-through at rest, on the page's own ground).
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
                    if (offset >= 0f) return@drawWithContent
                    clipRect(left = size.width + offset, top = 0f, right = size.width, bottom = size.height) {
                        drawRoundRect(swipeDanger(look), cornerRadius = CornerRadius(16.dp.toPx()))
                        this@drawWithContent.drawContent()
                    }
                },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(Modifier.padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(CosmoIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Remove", style = fonts.chip.copy(fontSize = 12.sp, fontWeight = FontWeight(800)), color = Color.White)
                }
            }
        },
    ) {
        // At rest the row is the flat Orbit row on the page's own ground (a cover's colour on Now Playing), not a pale square.
        content()
    }
}

@Composable
private fun QueueRow(
    track: QueueTrack,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    now: Boolean = false,
    playing: Boolean = false,
    progress: (() -> Float?)? = null,
    selected: Boolean? = null,
    handle: Modifier? = null,
    onRemove: (() -> Unit)? = null,
) {
    val song = track.song
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focusInside by remember { mutableStateOf(false) }
    OrbitSongRow(
        song.title, song.artist, rememberRemoteArtworkUrl(song), fonts, look, onClick = onClick, onLongClick = onLongClick, height = QUEUE_ROW_HEIGHT,
        modifier = modifier.fillMaxWidth().then(if (onRemove != null) Modifier.hoverable(hover).onFocusChanged { focusInside = it.hasFocus } else Modifier),
        now = now, playing = playing, progress = progress,
        discContent = selected?.let { on -> { OrbitCheckMark(on, look) } },
        trailing = {
            // The controls keep a column of their own: the words end 12 dp before it, ellipsized.
            if (onRemove != null || handle != null) Spacer(Modifier.width(12.dp))
            if (onRemove != null) {
                // Its place is kept while hidden, so the handle does not move when the pointer comes onto the row.
                Box(Modifier.size(36.dp).graphicsLayer { alpha = if (hovered || focusInside) 1f else 0f }) {
                    OrbitRoundButton(CosmoIcons.Close, "Remove from queue", look.dim, onRemove, size = 32.dp)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (handle != null) {
                Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(CosmoIcons.Queue, "Hold to move", tint = look.dim, modifier = Modifier.size(20.dp))
                }
            } else if (!now) OrbitRowTime(song.durationText, fonts, look)
        },
    )
}

@Composable
private fun GroupHead(title: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = fonts.label, color = look.dim, maxLines = 1, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun GroupAction(title: String, fonts: OrbitFonts, look: OrbitLook, enabled: Boolean, onClick: () -> Unit) {
    OrbitSelectChip(title, false, fonts, look, { if (enabled) onClick() }, haptic = enabled)
}

/** A queue row's height and the air between two rows. */
private val QUEUE_ROW_HEIGHT = 64.dp
private val QUEUE_ROW_GAP = 8.dp

/** What a swipe uncovers: the danger red, softened (no bright salmon), under a white glyph and word. */
private fun swipeDanger(look: OrbitLook): Color = if (look.dark) Color(0xFFA9403A) else Color(0xFFC0443B)
