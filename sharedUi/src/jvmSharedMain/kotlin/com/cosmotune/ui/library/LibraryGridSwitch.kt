package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem

/**
 * The one entry point of a Library shelf's "Show all" grid: [CosmoLibraryGrid]. The parameter list is upstream's `LibraryGridPage`'s,
 * name for name and in the same order (see [LibrarySwitch] for why).
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
    CosmoLibraryGrid(
        shelf = shelf, gridState = gridState, onItemClick = onItemClick, onItemLongPress = onItemLongPress,
        contentPadding = contentPadding, modifier = modifier, onNewPlaylist = onNewPlaylist,
    )
}
