package com.cosmotune.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.MoodGenrePlaylistsScreen

/**
 * The one entry point of a mood's or genre's page: the new page while the new look is on, the old [MoodGenrePlaylistsScreen]
 * while it is off.
 *
 * The parameter list is [MoodGenrePlaylistsScreen]'s, name for name and in the same order, and both branches pass every
 * argument by name, so a parameter that upstream adds to it stops the old branch compiling and gets decided on here.
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
    if (LocalCosmoShell.current != null) {
        CosmoMoodPage(
            title = title, state = state, listState = listState, onItemClick = onItemClick, onRetry = onRetry,
            contentPadding = contentPadding, modifier = modifier,
        )
    } else {
        MoodGenrePlaylistsScreen(
            title = title, state = state, listState = listState, onItemClick = onItemClick, onRetry = onRetry,
            contentPadding = contentPadding, modifier = modifier,
        )
    }
}
