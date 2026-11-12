package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.ui.screens.LibraryLink

/**
 * The one entry point of the Library page: [CosmoLibrary].
 *
 * The parameter list is upstream's `LibraryScreen`'s, name for name and in the same order, so upstream's call sites still merge;
 * when upstream adds one, add it here and decide whether the page needs it. The last four (the Songs chip and the Liked card's Play)
 * are the Orbit page's own.
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
    sourceShelves: List<HomeShelf> = com.cosmotune.ui.library.rememberSourceLibraryShelves(),
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    onSongClick: ((List<Song>, Int) -> Unit)? = null,
    onSongLongPress: ((Song) -> Unit)? = null,
) {
    CosmoLibrary(
        signedIn = signedIn, state = state, listState = listState, onShelfItemClick = onShelfItemClick,
        onShelfItemLongPress = onShelfItemLongPress, onNewPlaylist = onNewPlaylist,
        onImportSpotifyPlaylist = onImportSpotifyPlaylist, onShowAll = onShowAll, replay = replay, onSignIn = onSignIn,
        onRetry = onRetry, refreshing = refreshing, onRefresh = onRefresh, pullState = pullState, modifier = modifier,
        contentPadding = contentPadding, links = links, deviceItems = deviceItems, showTitle = showTitle, sourceShelves = sourceShelves,
        currentSong = currentSong, isPlaying = isPlaying, onSongClick = onSongClick, onSongLongPress = onSongLongPress,
    )
}
