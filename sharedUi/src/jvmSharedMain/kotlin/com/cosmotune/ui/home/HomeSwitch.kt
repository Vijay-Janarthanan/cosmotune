package com.cosmotune.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one entry point of Home: [OrbitHome].
 *
 * The parameter list is upstream's `HomeScreen`'s (name for name, type for type and in the same order), then [browse] (Explore's
 * moods, for the chip row), so upstream's call sites still merge; the parameters only the old screen read ([title], [leadHero]) are
 * accepted and ignored. When upstream adds a parameter to `HomeScreen`, add it here and decide whether [OrbitHome] needs it.
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
    discoveryHistory: List<Song> = emptyList(),
    discoveryLikedIds: Set<String> = emptySet(),
    followedArtists: List<com.cosmotune.listening.WatchedArtist> = emptyList(),
    quickLinks: List<HomeQuickLink> = emptyList(),
    onPlayPause: (() -> Unit)? = null,
    playbackTime: (() -> Pair<Long, Long>)? = null,
) {
    val region by com.cosmotune.music.data.recommendations.DiscoveryPreferences.state.collectAsState()
    val learning by com.cosmotune.music.data.recommendations.LocalPreferenceLearning.status.collectAsState()
    val personalShelves by produceState<List<HomeShelf>>(emptyList(), playlistSongs, discoveryHistory, discoveryLikedIds, followedArtists, region, state, learning.revision) {
        value = withContext(Dispatchers.Default) {
            com.cosmotune.music.data.recommendations.MusicCandidateService.updateContext(
                com.cosmotune.music.data.recommendations.MusicDiscoveryContext(playlistSongs, discoveryHistory, discoveryLikedIds, followedArtists))
            com.cosmotune.music.data.recommendations.DiscoveryFeed.personalShelves(region)
        }
    }
    val shownState = remember(state, personalShelves) {
        if (personalShelves.isEmpty()) state else {
            val current = (state as? UiState.Success)?.data.orEmpty()
            val sources = personalShelves.mapNotNull { it.sourceId }.toSet()
            UiState.Success(com.cosmotune.music.data.recommendations.DiscoveryFeed.order(current.filterNot { it.sourceId in sources } + personalShelves))
        }
    }
    var playlistOpen by remember { mutableStateOf(false) }
    val controls: @Composable () -> Unit = {
        HomeDiscoveryControls(onRetry, if (saveGeneratedPlaylist == null) null else ({ playlistOpen = true }))
        if (personalShelves.isNotEmpty() && state is UiState.Error) {
            Text("Online Home is unavailable. Showing saved local suggestions.", style = LocalCosmoType.current.meta, color = Color(LocalCosmoColors.current.palette.textSecondary), modifier = Modifier.padding(horizontal = CosmoSpacing.margin))
            KitTextButton("Retry online Home", onRetry, Modifier.padding(horizontal = 4.dp))
        } else if (personalShelves.isNotEmpty() && state is UiState.Loading) LinearProgressIndicator()
    }
    if (playlistOpen && saveGeneratedPlaylist != null) {
        PersonalPlaylistDialog(playlistSongs + (shownState as? UiState.Success)?.data.orEmpty().flatMap { shelf -> shelf.items.mapNotNull { it.song } },
            currentSong, { playlistOpen = false }, saveGeneratedPlaylist)
    }
    OrbitHome(
        state = shownState,
        listState = listState,
        onItemClick = onItemClick,
        onRetry = onRetry,
        refreshing = refreshing,
        onRefresh = onRefresh,
        pullState = pullState,
        modifier = modifier,
        contentPadding = contentPadding,
        signedIn = signedIn,
        onSignIn = onSignIn,
        onItemLongPress = onItemLongPress,
        onLoadMore = onLoadMore,
        loadingMore = loadingMore,
        recentlyPlayedLoading = recentlyPlayedLoading,
        currentSong = currentSong,
        isPlaying = isPlaying,
        discoveryControls = controls,
        browse = browse,
        quickLinks = quickLinks,
        onPlayPause = onPlayPause,
        playbackTime = playbackTime,
    )
}
