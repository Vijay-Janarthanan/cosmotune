package com.cosmotune.ui.search

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.search
import com.cosmotune.music.sharedui.resources.search_empty
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.PullToRefresh
import org.jetbrains.compose.resources.stringResource

/**
 * The browse page: Explore's moods and genres on their own, the idle page of Search without the field and the recent searches.
 * On the phone it is never shown (a mood opens its page straight from Search; the Explore tab has no place in the bar of three
 * tabs); on the desktop it is the Explore destination, which a mood page returns to.
 *
 * It takes [ExploreSwitch]'s parameters. A tap on a mood or genre calls [onCategoryClick]; pulling down calls [onRefresh] through
 * the same [PullToRefresh] the old page used. [showTitle] puts the page's heading ("Search") above the grid; the desktop's pages
 * carry none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CosmoBrowsePage(
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
    val lists = rememberBrowseLists(state)
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wideWidthDp = if (maxWidth.value >= WIDE_SEARCH_DP) maxWidth.value else null
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item(key = "browse:title") { CosmoPageHeader(title = stringResource(Res.string.search)) }
                }
                val shown = browseBlocks(state = state, lists = lists, onOpen = onCategoryClick, onRetry = onRetry, wideWidthDp = wideWidthDp)
                // A page that loaded and has no moods and no genres still says something, as the idle page does.
                if (!shown) item(key = "browse:empty") { MessageState(stringResource(Res.string.search_empty)) }
            }
        }
    }
}
