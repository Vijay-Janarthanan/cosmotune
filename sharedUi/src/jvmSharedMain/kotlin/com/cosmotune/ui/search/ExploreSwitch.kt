package com.cosmotune.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.UiState

/**
 * The one entry point of the Explore page: the browse page ([CosmoBrowsePage]: moods and genres, Music's second tab). The parameter
 * list is upstream's `ExploreScreen`'s, name for name and in the same order, so upstream's call sites still merge.
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
    CosmoBrowsePage(
        state = state, listState = listState, onCategoryClick = onCategoryClick, onRetry = onRetry, refreshing = refreshing,
        onRefresh = onRefresh, pullState = pullState, contentPadding = contentPadding, modifier = modifier, showTitle = showTitle,
    )
}
