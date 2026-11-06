package com.cosmotune.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.browse.BrowseSplit
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.ShimmerBox
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMoodTile
import com.cosmotune.ui.orbit.OrbitSectionHeader
import org.jetbrains.compose.resources.stringResource

/*
 * Explore's moods and genres as Orbit colour tiles (Canvas repo, design/cosmotune/pages-orbit/board.html, section 3): the idle
 * page of Search and the Explore page are both made of these.
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

/** The narrowest a tile is let get on a wide page, in dp, and the space between two tiles. */
internal const val TILE_MIN_DP = 200f
internal const val TILE_GAP_DP = 12f

/**
 * How many tiles stand in a row on a page [widthDp] wide with [sideDp] gutters: two on the phone, and from 600 dp on as many
 * [TILE_MIN_DP] tiles as fit, three to six.
 */
internal fun tileColumns(widthDp: Float, sideDp: Float): Int =
    if (widthDp < 600f) 2 else ((widthDp - 2 * sideDp + TILE_GAP_DP) / (TILE_MIN_DP + TILE_GAP_DP)).toInt().coerceIn(3, 6)

/** The skeleton stands for this many tiles. */
private const val SKELETON_TILES = 8

/**
 * Adds Explore's tiles to the list: with [together] one section, "Moods and genres" (Search's idle page), otherwise "Moods" and
 * "Genres" (the Explore page). While [state] loads they are skeleton tiles; after an error the message with Retry. A tap calls
 * [onOpen]. Returns whether it added anything: a success with no moods and no genres adds nothing, and the page then says what
 * it has instead.
 */
internal fun LazyListScope.browseTiles(
    state: UiState<List<MoodGenreSection>>,
    lists: BrowseLists?,
    onOpen: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    columns: Int,
    side: Dp,
    fonts: OrbitFonts,
    look: OrbitLook,
    together: Boolean,
): Boolean {
    val firstTitle = if (together) "Moods and genres" else "Moods"
    when (state) {
        UiState.Loading -> {
            header("browse:moods-title", firstTitle, side, fonts, look)
            itemsIndexed((0 until SKELETON_TILES).chunked(columns), key = { index, _ -> "browse:skeleton:$index" }) { index, row ->
                TileRow(index, row.size, columns, side) { ShimmerBox(Modifier.weight(1f).height(110.dp), RoundedCornerShape(26.dp)) }
            }
            return true
        }
        is UiState.Error -> {
            header("browse:moods-title", firstTitle, side, fonts, look)
            item(key = "browse:error") { MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry) }
            return true
        }
        is UiState.Success -> {
            if (lists == null || lists.isEmpty) return false
            if (together) {
                header("browse:moods-title", firstTitle, side, fonts, look)
                tiles("browse:tile", lists.moods + lists.genres, onOpen, columns, side, fonts, look)
            } else {
                if (lists.moods.isNotEmpty()) {
                    header("browse:moods-title", firstTitle, side, fonts, look)
                    tiles("browse:mood", lists.moods, onOpen, columns, side, fonts, look)
                }
                if (lists.genres.isNotEmpty()) {
                    header("browse:genres-title", "Genres", side, fonts, look)
                    tiles("browse:genre", lists.genres, onOpen, columns, side, fonts, look)
                }
            }
            return true
        }
    }
}

private fun LazyListScope.header(key: String, title: String, side: Dp, fonts: OrbitFonts, look: OrbitLook) {
    item(key = key) { OrbitSectionHeader(title, "", fonts, look, Modifier.padding(horizontal = side + 6.dp).padding(top = 30.dp, bottom = 14.dp)) }
}

/** [moods] as tiles, [columns] to a row; each row is one lazy item, keyed on its first tile. */
private fun LazyListScope.tiles(prefix: String, moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit, columns: Int, side: Dp, fonts: OrbitFonts, look: OrbitLook) {
    val rows = moods.chunked(columns.coerceAtLeast(1))
    itemsIndexed(rows, key = { index, row -> "$prefix:${row.first().browseId}|${row.first().params}#$index" }) { index, row ->
        TileRow(index, row.size, columns, side) { slot ->
            val mood = row[slot]
            OrbitMoodTile(mood.title, mood.thumbnailUrl, mood.stripeColor, fonts, look, onClick = { onOpen(mood) }, modifier = Modifier.weight(1f))
        }
    }
}

/** One row of tiles: [columns] places [TILE_GAP_DP] apart; a short last row keeps its empty places, so its tiles are as wide as the rest. */
@Composable
private fun TileRow(index: Int, size: Int, columns: Int, side: Dp, tile: @Composable androidx.compose.foundation.layout.RowScope.(slot: Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = side, end = side, top = if (index == 0) 0.dp else TILE_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        for (slot in 0 until columns) {
            if (slot < size) tile(slot) else Spacer(Modifier.weight(1f))
        }
    }
}
