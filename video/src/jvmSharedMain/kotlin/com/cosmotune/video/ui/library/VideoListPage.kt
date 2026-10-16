package com.cosmotune.video.ui.library

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_all
import com.cosmotune.video.resources.video_empty_liked
import com.cosmotune.video.resources.video_empty_watch_later
import com.cosmotune.video.resources.video_liked
import com.cosmotune.video.resources.video_move_down
import com.cosmotune.video.resources.video_move_up
import com.cosmotune.video.resources.video_remove
import com.cosmotune.video.resources.video_reorder
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import org.jetbrains.compose.resources.stringResource

/** The two local lists of videos that share a page (spec 5.8). */
enum class VideoListKind {
    /** Watch later: in your order, which you can change. */
    WATCH_LATER,

    /** Liked videos: the most recent first. */
    LIKED,
}

/** Watch later (spec 5.8), the nav host's branch. */
@Composable
fun WatchLaterPage(contentPadding: PaddingValues, wide: Boolean) = VideoListPage(VideoListKind.WATCH_LATER, contentPadding, wide)

/** Liked videos (spec 5.8), the nav host's branch. */
@Composable
fun LikedPage(contentPadding: PaddingValues, wide: Boolean) = VideoListPage(VideoListKind.LIKED, contentPadding, wide)

/**
 * Watch later or Liked videos (spec 5.8): the heading with "Download all" (spec 5.16, plan step 5b: one batch of the whole list, the
 * download sheet once), and for watch later the reorder switch; the videos as rows; a swipe either way removes one (and the row's
 * "Remove" action does the same for assistive technology). Watch later reorders: with the switch on, each row ends in a handle to drag
 * it, and "Move up" and "Move down" are its actions; the order is written once, when the row is let go. Empty: the empty state.
 */
@Composable
fun VideoListPage(kind: VideoListKind, contentPadding: PaddingValues, wide: Boolean) {
    val library = LocalVideoGraph.current.library
    val actions = rememberVideoActions()
    val flow = if (kind == VideoListKind.WATCH_LATER) library.watchLater else library.liked
    val stored by flow.collectAsState()
    val downloadedIds by rememberDownloadedIds()
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }
    var reordering by rememberSaveable(kind) { mutableStateOf(false) }
    val reorder = remember(kind) { ReorderState() }
    val listState = rememberLazyListState()
    val reduced = LocalReducedMotion.current

    val title = stringResource(if (kind == VideoListKind.WATCH_LATER) Res.string.video_watch_later else Res.string.video_liked)
    val downloadAll = stringResource(Res.string.video_dl_all)
    val reorderLabel = stringResource(Res.string.video_reorder)
    val removeLabel = stringResource(Res.string.video_remove)
    val upLabel = stringResource(Res.string.video_move_up)
    val downLabel = stringResource(Res.string.video_move_down)
    val shown = reorder.order ?: stored
    val current by rememberUpdatedState(shown)
    val canReorder = kind == VideoListKind.WATCH_LATER && reordering && stored.size > 1

    LibraryList(contentPadding, wide, listState) { cell ->
        item(key = "heading") {
            LibraryHeader(title, wide, cell) {
                if (stored.isNotEmpty()) {
                    CosmoIconButton(
                        CosmoIcons.Download,
                        downloadAll,
                        {
                            when (kind) {
                                VideoListKind.WATCH_LATER -> actions.downloadBatch(DownloadBatchInfo("watch_later", BatchKind.WATCH_LATER, title), stored, latestPicker = false)
                                VideoListKind.LIKED -> actions.downloadBatch(DownloadBatchInfo("liked", BatchKind.PLAYLIST, title), stored, latestPicker = false)
                            }
                        },
                        style = CosmoIconButtonStyle.TONAL,
                        size = 40.dp,
                    )
                }
                if (kind == VideoListKind.WATCH_LATER && stored.size > 1) {
                    CosmoIconButton(CosmoIcons.Sort, reorderLabel, { reordering = !reordering }, style = CosmoIconButtonStyle.TONAL, size = 40.dp, on = reordering)
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                CosmoEmptyState(
                    message = stringResource(if (kind == VideoListKind.WATCH_LATER) Res.string.video_empty_watch_later else Res.string.video_empty_liked),
                    modifier = cell,
                    icon = if (kind == VideoListKind.WATCH_LATER) CosmoIcons.Clock else CosmoIcons.HeartFill,
                )
            }
        }
        itemsIndexed(shown, key = { _, video -> "video:" + video.id }) { index, video ->
            val dragged = reorder.dragging == video.id
            val remove = { removeFrom(library, kind, video) }
            val moves = buildList {
                add(CustomAccessibilityAction(removeLabel) { remove(); true })
                if (canReorder && index > 0) add(CustomAccessibilityAction(upLabel) { library.moveInWatchLater(index, index - 1); true })
                if (canReorder && index < shown.lastIndex) add(CustomAccessibilityAction(downLabel) { library.moveInWatchLater(index, index + 1); true })
            }
            val placement = when {
                dragged -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offset }
                reduced -> Modifier
                else -> Modifier.animateItem()
            }
            SwipeToRemove(enabled = !canReorder, onRemove = remove, modifier = cell.then(placement)) {
                LibraryVideoRow(
                    item = video,
                    onClick = { actions.play(video, current) },
                    onMore = { sheetFor = video },
                    wide = wide,
                    modifier = Modifier.semantics { customActions = moves },
                    downloaded = video.id in downloadedIds,
                    trailing = if (canReorder) {
                        {
                            DragHandle(
                                Modifier.pointerInput(video.id) {
                                    detectDragGestures(
                                        onDragStart = { reorder.start(video.id, current) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            reorder.drag(amount.y, listState.layoutInfo)
                                        },
                                        onDragEnd = { reorder.end { from, to -> library.moveInWatchLater(from, to) } },
                                        onDragCancel = { reorder.end { from, to -> library.moveInWatchLater(from, to) } },
                                    )
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    // A move that was written shows the stored list again once the store has it (until then the dragged order stays, so nothing jumps back).
    LaunchedEffect(stored) { reorder.settled() }

    sheetFor?.let { video ->
        VideoActionsSheet(
            item = video,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(video, action, VideoSheetContext.DEFAULT, list = current) },
            onDismiss = { sheetFor = null },
            downloaded = video.id in downloadedIds,
        )
    }
}

private fun removeFrom(library: VideoLibrary, kind: VideoListKind, video: VideoItem) {
    when (kind) {
        VideoListKind.WATCH_LATER -> library.removeFromWatchLater(video.id)
        VideoListKind.LIKED -> library.setLiked(video, false)
    }
}

/**
 * A drag of watch later's rows (spec 5.8): while a row is held, [order] is the list as it will be when it is let go (the rows swap
 * as the held one passes their middles) and [offset] is how far the held row is drawn from its place (read only in the layer, so
 * the drag recomposes nothing per step). Letting go writes the one move from the first place to the last.
 */
@Stable
internal class ReorderState {
    /** The id of the held row, or null. */
    var dragging: String? by mutableStateOf(null)
        private set

    /** The list while a row is held, else null (the stored list shows). */
    var order: List<VideoItem>? by mutableStateOf(null)
        private set

    /** How far the held row is drawn from its place, in pixels. */
    var offset: Float by mutableFloatStateOf(0f)
        private set

    private var from = -1

    fun start(id: String, items: List<VideoItem>) {
        from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        order = items
        dragging = id
        offset = 0f
    }

    fun drag(dy: Float, layout: LazyListLayoutInfo) {
        val id = dragging ?: return
        val list = order ?: return
        offset += dy
        val held = layout.visibleItemsInfo.firstOrNull { it.key == "video:$id" } ?: return
        val current = list.indexOfFirst { it.id == id }
        val rows = layout.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            if (!key.startsWith("video:")) return@mapNotNull null
            val at = list.indexOfFirst { "video:" + it.id == key }
            if (at < 0) null else RowSpan(at, info.offset.toFloat(), info.size.toFloat())
        }
        val target = LibraryPlan.reorderTarget(current, held.offset + offset + held.size / 2f, rows)
        if (target == current) return
        val landing = rows.first { it.index == target }
        order = LibraryPlan.moved(list, current, target)
        // the held row is laid out at the place it moved to from the next frame on: keep it under the finger
        offset -= landing.top - held.offset
    }

    /** Lets the held row go: writes the move when it moved (the dragged order stays shown until [settled]), else shows the stored list. */
    fun end(commit: (from: Int, to: Int) -> Unit) {
        val id = dragging
        val list = order
        var moved = false
        if (id != null && list != null) {
            val to = list.indexOfFirst { it.id == id }
            if (from >= 0 && to >= 0 && to != from) {
                commit(from, to)
                moved = true
            }
        }
        dragging = null
        offset = 0f
        from = -1
        if (!moved) order = null
    }

    /** The stored list changed: when no row is held, it is the one to show. */
    fun settled() {
        if (dragging == null) order = null
    }
}

/** The handle at the end of a row of watch later while it is reordered: the sort mark in `textSecondary` on a 48 dp target, a drag's grip (the row's own actions move it for assistive technology). */
@Composable
private fun DragHandle(modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    Box(modifier.size(HANDLE_TARGET).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Icon(CosmoIcons.Sort, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(HANDLE_ICON))
    }
}

private val HANDLE_TARGET = 48.dp
private val HANDLE_ICON = 24.dp
