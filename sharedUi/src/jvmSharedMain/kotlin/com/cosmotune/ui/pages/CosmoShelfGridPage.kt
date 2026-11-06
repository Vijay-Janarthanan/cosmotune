package com.cosmotune.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitPortrait
import com.cosmotune.ui.orbit.OrbitRecord
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.blocks.shelfKey
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.ui.components.LIBRARY_GRID_SPACING
import com.cosmotune.music.ui.components.PAGE_GUTTER
import com.cosmotune.music.ui.components.libraryGrid
import com.cosmotune.music.ui.screens.localizeCardSubtitle

/**
 * Everything an artist's shelf holds, as a grid ("Show all" on an artist page), in Orbit: records (the cover as a disc), or round
 * portraits for a shelf of artists, at the width the Library's
 * "Show all" grid gives them ([libraryGrid]: two across on a phone, more on a wider window), run down the screen. Back is the root's (it clears the page's active shelf); the bar's room is [contentPadding]'s top.
 */
@Composable
fun CosmoShelfGridPage(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val circles = shelf.isArtists()
    val fonts = orbitFonts()
    val look = orbitLook()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // A feed can repeat a card: its place is part of its key.
            itemsIndexed(shelf.items, key = { index, item -> shelfKey(item, index) }) { _, item ->
                val held = onItemLongPress?.let { hold -> { hold(item) } }
                if (circles) {
                    OrbitPortrait(item.title, item.thumbnailUrl, grid.cardWidth, fonts, look, onClick = { onItemClick(item) }, onLongClick = held)
                } else {
                    // A record without the play moon: the page's only action on a card is to open it.
                    OrbitRecord(
                        title = item.title,
                        subtitle = item.subtitle.takeIf { it.isNotBlank() }?.let { localizeCardSubtitle(it) }.orEmpty(),
                        cover = item.thumbnailUrl,
                        fonts = fonts,
                        look = look,
                        onClick = { onItemClick(item) },
                        onPlay = null,
                        onLongClick = held,
                        size = grid.cardWidth,
                    )
                }
            }
        }
    }
}
