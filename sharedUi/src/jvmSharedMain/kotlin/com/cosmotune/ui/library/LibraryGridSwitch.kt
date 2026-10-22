package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.ui.screens.LibraryGridPage

/**
 * The one entry point of a Library shelf's "Show all" grid: the new grid while the new look is on, the old page while it is off.
 *
 * The parameter list is [LibraryGridPage]'s, name for name and in the same order, and both branches pass every argument by name
 * (see [LibrarySwitch] for why).
 */
@Composable
fun LibraryGridSwitch(
    shelf: HomeShelf,
    gridState: LazyGridState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onNewPlaylist: (() -> Unit)? = null,
) {
    if (LocalCosmoShell.current != null) {
        CosmoLibraryGrid(
            shelf = shelf, gridState = gridState, onItemClick = onItemClick, onItemLongPress = onItemLongPress,
            contentPadding = contentPadding, modifier = modifier, onNewPlaylist = onNewPlaylist,
        )
    } else {
        LibraryGridPage(
            shelf = shelf, gridState = gridState, onItemClick = onItemClick, onItemLongPress = onItemLongPress,
            contentPadding = contentPadding, modifier = modifier, onNewPlaylist = onNewPlaylist,
        )
    }
}
