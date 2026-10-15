package com.cosmotune.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.ExploreScreen

/**
 * The one entry point of the Explore page: with the new look on it is the browse page of Search (moods and genres, no longer a
 * tab of its own), with it off the old [ExploreScreen].
 *
 * The parameter list is [ExploreScreen]'s, name for name and in the same order, and both branches pass every argument by name,
 * so a parameter that upstream adds to [ExploreScreen] stops the old branch compiling and gets decided on here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreSwitch(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    if (LocalCosmoShell.current != null) {
        CosmoBrowsePage(
            state = state, listState = listState, onCategoryClick = onCategoryClick, onRetry = onRetry, refreshing = refreshing,
            onRefresh = onRefresh, pullState = pullState, contentPadding = contentPadding, modifier = modifier, showTitle = showTitle,
        )
    } else {
        ExploreScreen(
            state = state, listState = listState, onCategoryClick = onCategoryClick, onRetry = onRetry, refreshing = refreshing,
            onRefresh = onRefresh, pullState = pullState, contentPadding = contentPadding, modifier = modifier, showTitle = showTitle,
        )
    }
}
