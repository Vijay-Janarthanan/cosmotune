package com.cosmotune.music.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cosmotune.music.R
import com.cosmotune.music.data.model.ROW_ART_PX
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.UserPlaylist
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSheetPill
import com.cosmotune.ui.orbit.OrbitSheetTitle
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * Rearranges one of the account's playlists: every entry with a handle to drag
 * it by, and nothing sent until Save.
 *
 * Held locally and saved as one edit, rather than sent a move at a time while
 * dragging, because a drag passes through a dozen positions on the way to the
 * one it was meant for, and each of those would otherwise be a request that
 * could land out of order with the next.
 *
 * [entries] is the playlist as YouTube has it now — loading, failed, or the
 * list to start from. [saving] holds the form while the edit is out.
 */
@Composable
fun ReorderPlaylistSheet(
    playlist: UserPlaylist,
    entries: UiState<List<Song>>,
    saving: Boolean,
    onSave: (List<Song>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val original = (entries as? UiState.Success)?.data.orEmpty()
    val order = remember(original) { mutableStateListOf<Song>().apply { addAll(original) } }
    val changed = order.map { it.setVideoId } != original.map { it.setVideoId }

    val look = orbitLook()
    val fonts = orbitFonts()

    Column(modifier.fillMaxWidth().navigationBarsPadding()) {
        OrbitSheetTitle(
            title = stringResource(R.string.reorder_songs),
            subtitle = playlist.title,
            leading = { OrbitRoundButton(Icons.Rounded.Close, stringResource(R.string.back), look.ink, onClose) },
            trailing = {
                if (saving) {
                    OrbitRing(40.dp, look.ink, progress = null, spinning = true)
                } else {
                    OrbitSheetPill(
                        label = stringResource(R.string.save_order),
                        onClick = { onSave(order.toList()) },
                        primary = true,
                        enabled = changed,
                    )
                }
            },
        )

        when (entries) {
            UiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                OrbitRing(40.dp, look.ink, progress = null, spinning = true)
            }

            is UiState.Error -> Text(
                text = entries.message,
                style = fonts.body,
                color = look.dim,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
            )

            is UiState.Success -> ReorderableSongList(order, enabled = !saving)
        }
    }
}

/**
 * The drag itself, over a lazy list so a playlist of hundreds of tracks
 * doesn't compose all of them at once.
 *
 * The held row keeps two numbers: which entry it is ([held], by set-video-id)
 * and how far it is drawn from its own slot ([offset]). Each time its centre
 * crosses into a neighbour the two trade places in [order], and the offset is
 * reduced by exactly the distance its slot just moved, so the row stays under
 * the finger rather than jumping with the swap.
 */
/** The reorder list's share of the window's height: the sheet holds 85 % of it, its title and grabber the rest. */
private const val REORDER_LIST_SHARE = 0.68f

@Composable
private fun ReorderableSongList(order: MutableList<Song>, enabled: Boolean) {
    val listState = rememberLazyListState()
    var held by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgeZone = with(density) { 56.dp.toPx() }
    val edgeSpeed = with(density) { 900.dp.toPx() }

    fun settle() {
        val key = held ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: return
        val top = dragged.offset + offset
        val center = top + dragged.size / 2f
        val target = items.firstOrNull { item ->
            item.key != key && center >= item.offset && center <= item.offset + item.size
        }
        if (target != null) {
            val from = dragged.index
            val to = target.index
            // LazyColumn holds its place by the key of the first visible row;
            // moving that row would drag the whole list along with it, so the
            // current position is pinned explicitly across the swap.
            val first = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            order.add(to, order.removeAt(from))
            if (from == first || to == first) listState.requestScrollToItem(first, firstOffset)
            offset -= (target.offset - dragged.offset).toFloat()
        }
        // Near either edge the list scrolls under the held row, so a track
        // can be carried further than one screen.
        val viewport = listState.layoutInfo
        autoScroll = when {
            top < viewport.viewportStartOffset + edgeZone && listState.canScrollBackward -> -edgeSpeed
            top + dragged.size > viewport.viewportEndOffset - edgeZone && listState.canScrollForward -> edgeSpeed
            else -> 0f
        }
    }

    fun end() {
        held = null
        offset = 0f
        autoScroll = 0f
    }

    LaunchedEffect(autoScroll != 0f) {
        if (autoScroll == 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (held != null && autoScroll != 0f) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
            previous = now
            val scrolled = listState.scrollBy(autoScroll * seconds)
            if (scrolled == 0f) break
            // The list moved and the finger didn't: the row's slot went with
            // the list, so the row is drawn that much further from it.
            offset += scrolled
            settle()
        }
    }

    // Bounded: the Orbit sheet's surface scrolls its content, and a lazy list measured inside a scroll with no height of its own
    // crashes ("measured with an infinity maximum height"). Under the sheet's title, within the sheet's own 85 % of the window.
    val windowHeight = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = windowHeight * REORDER_LIST_SHARE)) {
        itemsIndexed(order, key = { _, song -> song.setVideoId ?: song.videoId }) { _, song ->
            val key = song.setVideoId ?: song.videoId
            val dragging = held == key
            val start: () -> Unit = {
                held = key
                offset = 0f
            }
            val drag: (Float) -> Unit = { delta ->
                offset += delta
                settle()
            }
            ReorderRow(
                song = song,
                lifted = dragging,
                enabled = enabled,
                onDragStart = start,
                onDrag = drag,
                onDragEnd = ::end,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f }
                    .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
            )
        }
        item(key = "reorder-footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReorderRow(
    song: Song,
    lifted: Boolean,
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = orbitLook()
    val fonts = orbitFonts()
    // Holding anywhere on the row picks it up too, not only the handle — the
    // handle is a small target, and a long press is what lists elsewhere on
    // the phone teach people to try first.
    val gestures = Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
            onDrag = { change, amount ->
                change.consume()
                onDrag(amount.y)
            },
        )
    }
    OrbitSongRow(
        title = song.title,
        subtitle = song.artist,
        cover = song.artworkAt(ROW_ART_PX),
        fonts = fonts,
        look = look,
        onClick = null,
        endPadding = 4.dp,
        // The held row stands off the list on a shadow; peach stays for what is playing.
        modifier = modifier
            .padding(vertical = 3.dp)
            .then(if (lifted) Modifier.shadow(12.dp, RoundedCornerShape(22.dp)) else Modifier)
            .then(gestures),
        titleContent = { ExplicitSongTitle(song = song, style = fonts.title, color = look.ink) },
        trailing = {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.drag_to_reorder),
                tint = look.dim,
                modifier = Modifier
                    .size(48.dp)
                    .padding(12.dp)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            },
                        )
                    },
            )
        },
    )
}
