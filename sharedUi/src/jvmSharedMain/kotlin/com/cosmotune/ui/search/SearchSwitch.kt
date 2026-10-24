package com.cosmotune.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.music.data.model.BrowseItem
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchHistoryEntity
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.ui.screens.SearchScreen

/**
 * The one entry point of Search: the new page (Search with Explore's moods and genres inside it) while the new look is on (the
 * shell exists), the old [SearchScreen] while it is off.
 *
 * The parameter list is [SearchScreen]'s, name for name and in the same order, plus [browse] at the end: Explore's moods and
 * genres, which the roots hand to the new page and the old screen never had. Both branches pass every argument by name. That is
 * the seam: when upstream adds a parameter to [SearchScreen] the old branch stops compiling, and this file is where the new
 * parameter has to be decided on, instead of silently going missing from the new page. With the new look off nothing here runs
 * but the old call.
 */
@Composable
fun SearchSwitch(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    currentSong: Song?,
    isPlaying: Boolean,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    onTypeaheadLongPress: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    topPadding: Dp,
    showField: Boolean = true,
    searchingLibrary: Boolean = false,
    libraryResults: UiState<List<Song>>? = null,
    onLibrarySongClick: (List<Song>, Int) -> Unit = onSongClick,
    sourceSwitcher: (@Composable () -> Unit)? = null,
    searchingAddons: Boolean = false,
    addonResults: UiState<List<Song>>? = null,
    addonHistory: List<String> = emptyList(),
    onAddonHistoryClick: (String) -> Unit = {},
    onAddonHistoryRemove: (String) -> Unit = {},
    onAddonHistoryClear: () -> Unit = {},
    onAddonSongClick: (List<Song>, Int) -> Unit = onSongClick,
    browse: CosmoBrowse? = null,
) {
    if (LocalCosmoShell.current != null) {
        CosmoSearch(
            query = query, onQueryChange = onQueryChange, filter = filter, currentSong = currentSong, isPlaying = isPlaying,
            onFilterChange = onFilterChange, results = results, loadingMore = loadingMore, onLoadMore = onLoadMore,
            listState = listState, scrollResetTrigger = scrollResetTrigger, focusRequested = focusRequested,
            onFocusHandled = onFocusHandled, onSongClick = onSongClick, onSongLongPress = onSongLongPress,
            onSongSwipe = onSongSwipe, onTopResultPlay = onTopResultPlay, onTopResultPlaylist = onTopResultPlaylist,
            onBrowseClick = onBrowseClick, onBrowseLongPress = onBrowseLongPress, history = history, suggestions = suggestions,
            typeaheadResults = typeaheadResults, onSubmit = onSubmit, onSuggestionClick = onSuggestionClick,
            onHistoryClick = onHistoryClick, onHistoryRemove = onHistoryRemove, onHistoryClear = onHistoryClear,
            onTypeaheadLongPress = onTypeaheadLongPress, modifier = modifier, contentPadding = contentPadding,
            topPadding = topPadding, showField = showField, searchingLibrary = searchingLibrary, libraryResults = libraryResults,
            onLibrarySongClick = onLibrarySongClick, sourceSwitcher = sourceSwitcher, searchingAddons = searchingAddons,
            addonResults = addonResults, addonHistory = addonHistory, onAddonHistoryClick = onAddonHistoryClick,
            onAddonHistoryRemove = onAddonHistoryRemove, onAddonHistoryClear = onAddonHistoryClear,
            onAddonSongClick = onAddonSongClick, browse = browse,
        )
    } else {
        SearchScreen(
            query = query, onQueryChange = onQueryChange, filter = filter, currentSong = currentSong, isPlaying = isPlaying,
            onFilterChange = onFilterChange, results = results, loadingMore = loadingMore, onLoadMore = onLoadMore,
            listState = listState, scrollResetTrigger = scrollResetTrigger, focusRequested = focusRequested,
            onFocusHandled = onFocusHandled, onSongClick = onSongClick, onSongLongPress = onSongLongPress,
            onSongSwipe = onSongSwipe, onTopResultPlay = onTopResultPlay, onTopResultPlaylist = onTopResultPlaylist,
            onBrowseClick = onBrowseClick, onBrowseLongPress = onBrowseLongPress, history = history, suggestions = suggestions,
            typeaheadResults = typeaheadResults, onSubmit = onSubmit, onSuggestionClick = onSuggestionClick,
            onHistoryClick = onHistoryClick, onHistoryRemove = onHistoryRemove, onHistoryClear = onHistoryClear,
            onTypeaheadLongPress = onTypeaheadLongPress, modifier = modifier, contentPadding = contentPadding,
            topPadding = topPadding, showField = showField, searchingLibrary = searchingLibrary, libraryResults = libraryResults,
            onLibrarySongClick = onLibrarySongClick, sourceSwitcher = sourceSwitcher, searchingAddons = searchingAddons,
            addonResults = addonResults, addonHistory = addonHistory, onAddonHistoryClick = onAddonHistoryClick,
            onAddonHistoryRemove = onAddonHistoryRemove, onAddonHistoryClear = onAddonHistoryClear,
            onAddonSongClick = onAddonSongClick,
        )
    }
}
