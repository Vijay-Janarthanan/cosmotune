package com.cosmotune.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.browse.BrowseSplit
import com.cosmotune.design.BlockMetrics
import com.cosmotune.ui.blocks.CosmoMoodTile
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.components.CosmoChip
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_search_genres
import com.cosmotune.music.sharedui.resources.cosmo_search_moods
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.ShimmerBox
import org.jetbrains.compose.resources.stringResource

/*
 * The moods grid and the genre chips of Explore, as Search shows them: the idle page of Search and the browse page (the Explore
 * destination of the desktop) are both made of these (`V2.search`, `v2/v2.js:68-75`; `screens-v2.css` `.moodgrid`, `.chips-row`).
 */

/** Explore's buttons split the way the page shows them: the first section is the moods, every other section is a genre. */
@Immutable
internal class BrowseLists(val moods: List<MoodGenre>, val genres: List<MoodGenre>) {
    val isEmpty: Boolean get() = moods.isEmpty() && genres.isEmpty()
}

/** The split of [state] while it is a success, null in every other state. It is made once per state, not per recomposition. */
@Composable
internal fun rememberBrowseLists(state: UiState<List<MoodGenreSection>>?): BrowseLists? =
    remember(state) { (state as? UiState.Success)?.data?.let { BrowseLists(BrowseSplit.moods(it), BrowseSplit.genres(it)) } }

/** The skeleton of the grid stands for this many tiles (the brief: six). */
private const val SKELETON_TILES = 6

/**
 * Adds the blocks of the browse part of a page to the list: "Browse by mood" with the moods grid, then "Genres" with the genre
 * chips. While [state] loads the grid is six skeleton tiles; after an error it is the message with a retry button. Tapping a
 * mood or a genre calls [onOpen]. Returns whether it added anything: a success with no moods and no genres adds nothing, and the
 * idle page then has to say what it has instead. [wideWidthDp] is the width of the page when it is wide (see [moodGridRows]), null on the phone.
 */
internal fun LazyListScope.browseBlocks(
    state: UiState<List<MoodGenreSection>>,
    lists: BrowseLists?,
    onOpen: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    wideWidthDp: Float? = null,
): Boolean {
    when (state) {
        UiState.Loading -> {
            moodsTitle()
            skeletonRows(wideWidthDp)
            return true
        }
        is UiState.Error -> {
            moodsTitle()
            item(key = "browse:error") {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            return true
        }
        is UiState.Success -> {
            if (lists == null || lists.isEmpty) return false
            if (lists.moods.isNotEmpty()) {
                moodsTitle()
                moodRows(lists.moods, onOpen, wideWidthDp)
            }
            if (lists.genres.isNotEmpty()) {
                item(key = "browse:genres-title") { CosmoOverline(stringResource(Res.string.cosmo_search_genres)) }
                item(key = "browse:genres") { GenreChips(lists.genres, onOpen) }
            }
            return true
        }
    }
}

private fun LazyListScope.moodsTitle() {
    item(key = "browse:moods-title") { CosmoOverline(stringResource(Res.string.cosmo_search_moods)) }
}

/** One row of the mood grid: the moods from [first] on, [size] of them, [big] or not, in a row of [slots] places. */
@Immutable
internal class MoodGridRow(val first: Int, val size: Int, val big: Boolean, val slots: Int)

/** The narrowest a tile is let get on a wide page, in dp. */
internal const val WIDE_MOOD_TILE_MIN_DP = 220f

/**
 * The rows of a grid of [count] moods. On the phone ([wideWidthDp] null) they are [BlockMetrics.moodRows]: the first two big,
 * two to a row, then three to a row. On a wide page (the desktop, a tablet) two tiles as wide as the window would be banners, so
 * every tile is the small one and the row takes as many as fit at [WIDE_MOOD_TILE_MIN_DP] each (three to six).
 */
internal fun moodGridRows(count: Int, wideWidthDp: Float?): List<MoodGridRow> {
    if (wideWidthDp == null) return BlockMetrics.moodRows(count).map { MoodGridRow(it.first, it.size, it.big, it.slots) }
    val gap = BlockMetrics.MOOD_GAP_DP
    val room = wideWidthDp - 2 * BlockMetrics.MOOD_MARGIN_DP + gap
    val columns = (room / (WIDE_MOOD_TILE_MIN_DP + gap)).toInt().coerceIn(3, BlockMetrics.MOOD_COLUMNS)
    return (0 until count step columns).map { first -> MoodGridRow(first, minOf(columns, count - first), big = false, slots = columns) }
}

/** The moods in [moodGridRows]. */
private fun LazyListScope.moodRows(moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit, wideWidthDp: Float?) {
    val rows = moodGridRows(moods.size, wideWidthDp)
    itemsIndexed(rows, key = { _, row -> moods[row.first].let { "browse:mood-row:${it.browseId}|${it.params}" } }) { index, row ->
        MoodGridRowContent(row = row, first = index == 0) { slot ->
            val mood = moods[row.first + slot]
            CosmoMoodTile(
                label = mood.title,
                coverUrl = mood.thumbnailUrl,
                onClick = { onOpen(mood) },
                modifier = Modifier.weight(1f),
                big = row.big,
            )
        }
    }
}

private fun LazyListScope.skeletonRows(wideWidthDp: Float?) {
    val rows = moodGridRows(SKELETON_TILES, wideWidthDp)
    itemsIndexed(rows, key = { index, _ -> "browse:skeleton:$index" }) { index, row ->
        val shape = remember { SuperellipseShape(BlockMetrics.MOOD_TILE_RADIUS_DP.dp) }
        MoodGridRowContent(row = row, first = index == 0) {
            ShimmerBox(Modifier.weight(1f).height(BlockMetrics.moodTileHeightDp(row.big).dp), shape)
        }
    }
}

/**
 * One row of the grid: [MoodGridRow.slots] places 10 dp apart, 16 dp in from each side; a row that is not full keeps its last
 * places empty, so a lone tile has the width of a full row's. [tile] draws the place [slot] (0 until `size`).
 */
@Composable
private fun MoodGridRowContent(
    row: MoodGridRow,
    first: Boolean,
    tile: @Composable androidx.compose.foundation.layout.RowScope.(slot: Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = if (first) 0.dp else BlockMetrics.MOOD_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(BlockMetrics.MOOD_GAP_DP.dp),
    ) {
        for (slot in 0 until row.slots) {
            if (slot < row.size) tile(slot) else Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The genres as chips that wrap (`.chips-row`: gap 8, 16 dp in from each side). A chip's touch target is 48 dp tall though the
 * chip is 36, so the rows stand 48 dp apart with no gap added: the visible space between two rows is 12 dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<MoodGenre>, onOpen: (MoodGenre) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (genre in genres) {
            key("${genre.browseId}|${genre.params}") {
                CosmoChip(label = genre.title, selected = false, onClick = { onOpen(genre) })
            }
        }
    }
}
