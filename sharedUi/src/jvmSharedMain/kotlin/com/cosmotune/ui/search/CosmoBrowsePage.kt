package com.cosmotune.ui.search

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.search_empty
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import org.jetbrains.compose.resources.stringResource

/**
 * The Explore page in Orbit: the heading, then Explore's moods and genres as colour tiles with a disc peeking out of the corner
 * (the same tiles as Search's idle page). It is Music's second tab on the phone and the Explore destination on the desktop; a mood
 * page returns to it.
 *
 * It takes [ExploreSwitch]'s parameters. A tap on a mood or genre calls [onCategoryClick]; pulling down calls [onRefresh] through
 * the same [PullToRefresh] the old page used. [showTitle] is not read: an Orbit page carries its heading on the phone and the
 * desktop alike, as Home and the Library do.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = if (maxWidth.value >= WIDE_SEARCH_DP) 28.dp else 16.dp
            val columns = tileColumns(maxWidth.value, side.value)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "browse:title") {
                    val count = lists?.let { it.moods.size + it.genres.size } ?: 0
                    OrbitPageHeading(
                        title = "Explore",
                        label = if (count > 0) "Moods and genres · $count" else "Moods and genres",
                        fonts = fonts,
                        look = look,
                        modifier = Modifier.padding(start = side + 6.dp, end = side, top = 18.dp),
                    )
                }
                val shown = browseTiles(state = state, lists = lists, onOpen = onCategoryClick, onRetry = onRetry, columns = columns, side = side, fonts = fonts, look = look, together = false)
                // A page that loaded and has no moods and no genres still says something, as the idle page does.
                if (!shown) item(key = "browse:empty") { MessageState(stringResource(Res.string.search_empty)) }
                item(key = "browse:end") { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}
