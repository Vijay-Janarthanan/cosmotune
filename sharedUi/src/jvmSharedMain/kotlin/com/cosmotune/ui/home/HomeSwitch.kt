package com.cosmotune.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.ui.screens.HomeScreen

/**
 * The one entry point of Home: the new screen while the new look is on (the shell exists), the old one while it is off.
 *
 * The parameter list is [HomeScreen]'s, name for name, type for type and in the same order, then [browse] (Explore's moods, for the
 * chip row of the new screen; the old one has no use for it). Both branches pass every argument by name. That is the seam: when
 * upstream adds a parameter to [HomeScreen] the old branch stops compiling, and this file is where the new parameter has to be
 * decided on, instead of silently going missing from the new screen. With the new look off nothing here runs but the old call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSwitch(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String?,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    leadHero: Boolean = true,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    browse: CosmoBrowse? = null,
    playlistSongs: List<Song> = emptyList(),
    saveGeneratedPlaylist: (suspend (String, List<Song>) -> Unit)? = null,
) {
    var playlistOpen by remember { mutableStateOf(false) }
    val controls: @Composable () -> Unit = {
        HomeDiscoveryControls(onRetry, if (saveGeneratedPlaylist == null) null else ({ playlistOpen = true }))
    }
    if (playlistOpen && saveGeneratedPlaylist != null) {
        PersonalPlaylistDialog(playlistSongs + (state as? UiState.Success)?.data.orEmpty().flatMap { shelf -> shelf.items.mapNotNull { it.song } },
            currentSong, { playlistOpen = false }, saveGeneratedPlaylist)
    }
    if (LocalCosmoShell.current != null) {
        CosmoHome(
            state = state,
            listState = listState,
            onItemClick = onItemClick,
            onRetry = onRetry,
            refreshing = refreshing,
            onRefresh = onRefresh,
            pullState = pullState,
            modifier = modifier,
            contentPadding = contentPadding,
            title = title,
            signedIn = signedIn,
            onSignIn = onSignIn,
            onItemLongPress = onItemLongPress,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            recentlyPlayedLoading = recentlyPlayedLoading,
            leadHero = leadHero,
            currentSong = currentSong,
            isPlaying = isPlaying,
            browse = browse,
            discoveryControls = controls,
        )
    } else {
        HomeScreen(
            discoveryControls = controls,
            state = state,
            listState = listState,
            onItemClick = onItemClick,
            onRetry = onRetry,
            refreshing = refreshing,
            onRefresh = onRefresh,
            pullState = pullState,
            modifier = modifier,
            contentPadding = contentPadding,
            title = title,
            signedIn = signedIn,
            onSignIn = onSignIn,
            onItemLongPress = onItemLongPress,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            recentlyPlayedLoading = recentlyPlayedLoading,
            leadHero = leadHero,
            currentSong = currentSong,
            isPlaying = isPlaying,
        )
    }
}
