package com.cosmotune.video.ui.playlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.LocalCosmoColors
import com.music.bitchord.sharedui.resources.drag_to_reorder
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes

/*
 * Reordering the rows of your own playlist (spec 5.6): a handle at the end of the row is dragged, the rows it passes slide aside, the
 * list scrolls when the row is held near its edge. The playlist itself is reordered row by row through the library's `moveInPlaylist`,
 * so what is on screen is always what is stored.
 */

/** A visible row of the list: its place among the playlist's rows, its top in the list's coordinates and its height, in pixels. */
internal data class RowBounds(val index: Int, val top: Int, val size: Int)

/** The arithmetic of a drag, apart from any window. */
internal object ReorderMath {
    /**
     * The row the held row (the one at [heldIndex]) has been carried onto: the first of [rows] other than itself that contains
     * [heldCenter] (the middle of the held row, in the list's coordinates), or null when the middle is over no other row.
     */
    fun target(heldIndex: Int, heldCenter: Float, rows: List<RowBounds>): Int? {
        for (row in rows) {
            if (row.index != heldIndex && heldCenter >= row.top && heldCenter < row.top + row.size) return row.index
        }
        return null
    }

    /**
     * How far to scroll for one frame while a row is held: the held row spans [heldTop] to [heldBottom], the visible part of the list
     * [viewportStart] to [viewportEnd]. Within [threshold] of the top edge it is up (negative), within it of the bottom edge down
     * (positive), faster the closer to (or past) the edge, at most [maxStep]; 0 anywhere else.
     */
    fun autoScroll(heldTop: Float, heldBottom: Float, viewportStart: Float, viewportEnd: Float, threshold: Float, maxStep: Float): Float {
        if (threshold <= 0f) return 0f
        val above = viewportStart + threshold - heldTop
        if (above > 0f) return -(above / threshold).coerceAtMost(1f) * maxStep
        val below = heldBottom - (viewportEnd - threshold)
        if (below > 0f) return (below / threshold).coerceAtMost(1f) * maxStep
        return 0f
    }
}

/**
 * A drag of one row of [listState]'s list. The playlist's rows are the list's items [firstRow] until [firstRow] + [rowCount]; a row
 * is held by its key. [start] begins holding it, [drag] moves it with the finger and, once it lies over another row, calls [onMove] with
 * the row's old and new place; the library reorders, the list is composed again and the held row (whose key is the same) is found in
 * its new place. [translationOf] is how far the held row's layer sits from where the list has it, read in the layer block.
 */
@Stable
internal class ReorderState(
    private val listState: LazyListState,
    private val firstRow: Int,
    private val rowCount: () -> Int,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The key of the row being held, or null. */
    var heldKey: Any? by mutableStateOf(null)
        private set

    /** The held row's top in the list's coordinates: it follows the finger, whatever the list does underneath. */
    private var heldTop by mutableFloatStateOf(0f)

    /** The place the last move put the held row at, until the list shows it there (so one move is not asked for twice). */
    private var awaitingIndex = -1
    private var awaitingSinceMs = 0L

    private fun infoOf(key: Any): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun start(key: Any) {
        val info = infoOf(key) ?: return
        heldKey = key
        heldTop = info.offset.toFloat()
        awaitingIndex = -1
    }

    fun drag(dy: Float) {
        if (heldKey == null) return
        heldTop += dy
        carry()
    }

    fun end(key: Any) {
        if (heldKey == key) heldKey = null
    }

    /** How far to move the layer of the row [key] from its place in the list: 0 unless it is the held one. Read in a layer block. */
    fun translationOf(key: Any): Float {
        if (key != heldKey) return 0f
        val info = infoOf(key) ?: return 0f
        return heldTop - info.offset
    }

    /** Moves the held row to the place of the row its middle lies over, if it lies over one. */
    fun carry() {
        val key = heldKey ?: return
        val held = infoOf(key) ?: return
        if (awaitingIndex >= 0) {
            if (held.index != awaitingIndex && nowMs() - awaitingSinceMs < AWAIT_MS) return
            awaitingIndex = -1
        }
        val rows = listState.layoutInfo.visibleItemsInfo
            .filter { it.index >= firstRow && it.index < firstRow + rowCount() }
            .map { RowBounds(it.index, it.offset, it.size) }
        val target = ReorderMath.target(held.index, heldTop + held.size / 2f, rows) ?: return
        awaitingIndex = target
        awaitingSinceMs = nowMs()
        onMove(held.index - firstRow, target - firstRow)
    }

    /** The scroll for this frame while a row is held (see [ReorderMath.autoScroll]); 0 when none is held. */
    fun edgeStep(thresholdPx: Float, maxStepPx: Float): Float {
        val key = heldKey ?: return 0f
        val held = infoOf(key) ?: return 0f
        val layout = listState.layoutInfo
        return ReorderMath.autoScroll(
            heldTop = heldTop,
            heldBottom = heldTop + held.size,
            viewportStart = (layout.viewportStartOffset + layout.beforeContentPadding).toFloat(),
            viewportEnd = (layout.viewportEndOffset - layout.afterContentPadding).toFloat(),
            threshold = thresholdPx,
            maxStep = maxStepPx,
        )
    }

    companion object {
        /** A move that the list has not shown after this long is forgotten, so a refused move cannot freeze the drag. */
        const val AWAIT_MS = 300L
    }
}

/** While a row is held near the edge of the list, scrolls the list a little each frame and lets the held row carry on from there. */
@Composable
internal fun ReorderAutoScroll(state: ReorderState, listState: LazyListState, thresholdPx: Float, maxStepPx: Float) {
    val held = state.heldKey
    LaunchedEffect(held) {
        if (held == null) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val step = state.edgeStep(thresholdPx, maxStepPx)
            if (step != 0f) {
                listState.scrollBy(step)
                state.carry()
            }
        }
    }
}

/** The handle's box: 40 dp wide, 48 dp tall (a touch target). */
private val HANDLE_WIDTH = 40.dp
private val HANDLE_HEIGHT = 48.dp

/** The six dots of the handle: two columns, three rows, 16 x 20 dp. */
private val GLYPH_WIDTH = 16.dp
private val GLYPH_HEIGHT = 20.dp
private val DOT_RADIUS = 1.7.dp

/**
 * The handle at the end of a row of your own playlist (spec 5.6): six dots in `textTertiary`, dragged to move the row. [onStart] begins
 * the drag, [onDrag] gets the finger's vertical travel since the last call, [onEnd] ends it (also when it is cancelled). Described as
 * "Drag to reorder".
 */
@Composable
internal fun DragHandle(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val ink = Color(LocalCosmoColors.current.palette.textTertiary)
    val label = stringResource(SharedRes.string.drag_to_reorder)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier
            .size(HANDLE_WIDTH, HANDLE_HEIGHT)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onDrag = { change, amount ->
                        change.consume()
                        drag(amount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(GLYPH_WIDTH, GLYPH_HEIGHT)) {
            val radius = DOT_RADIUS.toPx()
            for (column in 0..1) {
                for (row in 0..2) {
                    drawCircle(ink, radius, Offset(size.width * (0.25f + 0.5f * column), size.height * (0.17f + 0.33f * row)))
                }
            }
        }
    }
}
