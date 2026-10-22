package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.ui.screens.LibraryLink
import com.cosmotune.music.ui.screens.LibraryScreen

/**
 * The one entry point of the Library page: the new page while the new look is on (the shell exists), the old one while it is off.
 *
 * The parameter list is [LibraryScreen]'s, name for name and in the same order, and both branches pass every argument by name.
 * That is the seam: when upstream adds a parameter to [LibraryScreen] the old branch stops compiling, and this file is where the
 * new parameter has to be decided on, instead of silently going missing from the new page. With the new look off nothing here
 * runs but the old call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySwitch(
    signedIn: Boolean,
    state: UiState<LibraryPage>,
    listState: LazyListState,
    onShelfItemClick: (ShelfItem) -> Unit,
    onShelfItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)? = null,
    onShowAll: (HomeShelf) -> Unit,
    replay: @Composable () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    links: List<LibraryLink>,
    deviceItems: List<ShelfItem>,
    showTitle: Boolean = true,
) {
    if (LocalCosmoShell.current != null) {
        CosmoLibrary(
            signedIn = signedIn, state = state, listState = listState, onShelfItemClick = onShelfItemClick,
            onShelfItemLongPress = onShelfItemLongPress, onNewPlaylist = onNewPlaylist,
            onImportSpotifyPlaylist = onImportSpotifyPlaylist, onShowAll = onShowAll, replay = replay, onSignIn = onSignIn,
            onRetry = onRetry, refreshing = refreshing, onRefresh = onRefresh, pullState = pullState, modifier = modifier,
            contentPadding = contentPadding, links = links, deviceItems = deviceItems, showTitle = showTitle,
        )
    } else {
        LibraryScreen(
            signedIn = signedIn, state = state, listState = listState, onShelfItemClick = onShelfItemClick,
            onShelfItemLongPress = onShelfItemLongPress, onNewPlaylist = onNewPlaylist,
            onImportSpotifyPlaylist = onImportSpotifyPlaylist, onShowAll = onShowAll, replay = replay, onSignIn = onSignIn,
            onRetry = onRetry, refreshing = refreshing, onRefresh = onRefresh, pullState = pullState, modifier = modifier,
            contentPadding = contentPadding, links = links, deviceItems = deviceItems, showTitle = showTitle,
        )
    }
}
