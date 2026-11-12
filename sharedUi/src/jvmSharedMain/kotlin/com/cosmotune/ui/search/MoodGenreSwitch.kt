package com.cosmotune.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.UiState

/**
 * The one entry point of a mood's or genre's page: [CosmoMoodPage]. The parameter list is upstream's `MoodGenrePlaylistsScreen`'s,
 * name for name and in the same order, so upstream's call sites still merge.
 */
@Composable
fun MoodGenreSwitch(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    CosmoMoodPage(
        title = title, state = state, listState = listState, onItemClick = onItemClick, onRetry = onRetry,
        contentPadding = contentPadding, modifier = modifier,
    )
}
