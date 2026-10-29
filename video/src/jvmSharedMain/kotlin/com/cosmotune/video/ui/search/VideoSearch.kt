package com.cosmotune.video.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_filter_all
import com.cosmotune.video.resources.video_filter_channels
import com.cosmotune.video.resources.video_filter_playlists
import com.cosmotune.video.resources.video_filter_shorts
import com.cosmotune.video.resources.video_filter_videos
import com.cosmotune.video.resources.video_search_hint
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.ui.blocks.rememberSubscribeToggle
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.desktop.DesktopVideoState
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shorts.ShortsGrid
import com.cosmotune.video.ui.shorts.openShort
import org.jetbrains.compose.resources.stringResource

/*
 * Search (spec 5.3, plan task Y-18). One page that is, by what the field holds, the idle page (the recent searches), the typing page
 * (suggestions, then the recent searches that match) or the results of the route's query. The route's query is what was searched;
 * the field's text is what is being typed, and the two are the same once a search has been submitted.
 */

/** The three pages the field's text can ask for. */
internal enum class SearchMode { IDLE, TYPING, RESULTS }

/**
 * Which page [typed] (the field's text) asks for when [submitted] (the route's query) is what was searched last: nothing typed is
 * the idle page, whatever was searched; the text of the search (spaces around it do not count) is its results; anything else is the
 * typing page.
 */
internal fun searchMode(typed: String, submitted: String): SearchMode = when {
    typed.isBlank() -> SearchMode.IDLE
    typed.trim() == submitted.trim() -> SearchMode.RESULTS
    else -> SearchMode.TYPING
}

/** The filters the chips offer, in order: Shorts is not among them while "Hide Shorts" is on. */
internal fun searchFilters(hideShorts: Boolean): List<SearchFilter> =
    if (hideShorts) {
        listOf(SearchFilter.ALL, SearchFilter.VIDEOS, SearchFilter.CHANNELS, SearchFilter.PLAYLISTS)
    } else {
        listOf(SearchFilter.ALL, SearchFilter.VIDEOS, SearchFilter.SHORTS, SearchFilter.CHANNELS, SearchFilter.PLAYLISTS)
    }

/**
 * The search page for [route] (spec 5.3). On the phone, and on a desktop window too narrow for the sidebar, a 52 dp field stands at
 * the top and is focused when the route opens with no query; on the desktop the sidebar's field is the field (its text is
 * `DesktopVideoState.query`, which this page keeps equal to the route's query). Typing for 250 ms asks the source for suggestions;
 * a submit adds the query to the recent searches and replaces the page with the route of the new query. The results come with the
 * filter chips (All, Videos, Shorts, Channels, Playlists), a video opens its watch page, a Short the pager with the search's
 * Shorts, a channel or a playlist its page. [wide] pages draw the rows with the desktop's thumbnail in a column of at most 840 dp.
 *
 * [contentPadding] is the room the shell keeps clear: its top above the field and its bottom under the last row.
 */
@Composable
fun VideoSearch(route: VideoRoute.Search, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val library = graph.library
    val source = graph.source
    val actions = rememberVideoActions()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val settings by graph.settings.settings.collectAsState()
    val hideShorts = settings.hideShorts
    val recents by library.searches.collectAsState()
    val subscriptions by library.subscriptions.collectAsState()
    val downloaded by rememberDownloadedIds()
    val recentSearches = remember(recents) { recents.distinct() }
    val subscribed = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.channelId } }
    val subscribeToggle = rememberSubscribeToggle()

    // What is typed: in the page's own field, or in the desktop sidebar's (which the route's query drives).
    val sidebar = sidebarSearchShown()
    var own by rememberSaveable(route.query) { mutableStateOf(route.query) }
    val typed = if (sidebar) DesktopVideoState.query else own
    val submitted = route.query.trim()
    val mode = searchMode(typed, submitted)
    // Opened by typing in the sidebar from another page (Y-34): the typed text stays, and the page shows its suggestions.
    if (sidebar) LaunchedEffect(route.query) { if (!DesktopVideoState.takeKeepTyped()) DesktopVideoState.query = route.query }
    if (!sidebar) {
        LaunchedEffect(Unit) {
            if (route.query.isEmpty()) {
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }
        }
    }

    fun submit(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        library.addSearch(query)
        navigator.replaceTop(VideoRoute.Search(query))
    }

    fun pick(text: String) {
        focusManager.clearFocus()
        keyboard?.hide()
        submit(text)
    }

    fun fill(text: String) {
        if (sidebar) {
            DesktopVideoState.query = text
            DesktopVideoState.requestSearchFocus()
        } else {
            own = text
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    // The filter chosen (by name: the list of chips changes with "Hide Shorts"); a new search starts at All.
    var filterName by rememberSaveable { mutableStateOf(SearchFilter.ALL.name) }
    val filters = searchFilters(hideShorts)
    val filter = filters.firstOrNull { it.name == filterName } ?: SearchFilter.ALL

    // The results of this search with this filter: the tab the session cache holds for them, loaded when the page shows them.
    val tab = if (mode == SearchMode.RESULTS) remember(source, submitted, filter) { SearchSessions.tab(source, submitted, filter) } else null
    if (tab != null) LaunchedEffect(tab) { tab.start(scope) }
    val visible: List<ListItem> = if (tab != null) remember(tab.items, hideShorts) { visibleResults(tab.items, hideShorts) } else emptyList()
    val videos = remember(visible) { visible.mapNotNull { (it as? ListItem.Video)?.item } }

    val suggestions by rememberSuggestions(source, typed, active = mode == SearchMode.TYPING)
    val rows = remember(typed, suggestions, recentSearches) { SuggestionRows.build(typed, suggestions, recentSearches) }

    var sheet by remember { mutableStateOf<VideoItem?>(null) }
    val callbacks = SearchRowCallbacks(
        onVideo = { item ->
            if (item.isShort) navigator.openShort(item, ShortsOrigin.Search(submitted), videos) else actions.play(item, videos)
        },
        onMore = { item -> sheet = item },
        onChannel = { channel -> navigator.push(VideoRoute.Channel(channel.id)) },
        onSubscribe = { channel -> subscribeToggle(channel, channel.id in subscribed) },
        onPlaylist = { playlist -> navigator.push(VideoRoute.Playlist(playlist.id)) },
    )

    Column(
        Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!sidebar) {
            CosmoSearchField(
                query = typed,
                onQueryChange = { own = it },
                onSubmit = { submit(own) },
                placeholder = stringResource(Res.string.video_search_hint),
                modifier = Modifier.listWidth(wide).padding(horizontal = CosmoSpacing.margin).padding(bottom = FIELD_GAP),
                focusRequester = focusRequester,
            )
        }
        val bottom = contentPadding.calculateBottomPadding()
        when (mode) {
            SearchMode.IDLE -> LazyColumn(Modifier.weight(1f).listWidth(wide), contentPadding = PaddingValues(bottom = bottom)) {
                recentRows(
                    recents = recentSearches,
                    onPick = ::pick,
                    onRemove = { library.removeSearch(it) },
                    onClear = { library.clearSearches() },
                )
            }
            SearchMode.TYPING -> LazyColumn(Modifier.weight(1f).listWidth(wide), contentPadding = PaddingValues(bottom = bottom)) {
                suggestionRows(rows, typed, onPick = ::pick, onFill = ::fill)
            }
            SearchMode.RESULTS -> {
                val labels = filters.map { filterLabel(it) }
                VideoChips(
                    labels = labels,
                    selected = filters.indexOf(filter),
                    onSelect = { filterName = filters[it].name },
                    modifier = Modifier.listWidth(wide),
                )
                if (tab != null) {
                    if (filter == SearchFilter.SHORTS && tab.phase == SearchPhase.READY && videos.isNotEmpty()) {
                        val side = if (wide) VideoMetrics.DESKTOP_MARGIN_DP.dp else CosmoSpacing.margin
                        ShortsGrid(
                            items = videos,
                            onOpen = { navigator.openShort(it, ShortsOrigin.Search(submitted), videos) },
                            onLoadMore = { tab.loadMore(scope) },
                            contentPadding = PaddingValues(start = side, end = side, bottom = bottom),
                            wide = wide,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        ResultsList(
                            tab = tab,
                            visible = visible,
                            wide = wide,
                            callbacks = callbacks,
                            subscribed = subscribed,
                            downloaded = downloaded,
                            bottomPadding = bottom,
                            scope = scope,
                            modifier = Modifier.weight(1f).listWidth(wide),
                        )
                    }
                }
            }
        }
    }

    sheet?.let { item ->
        VideoActionsSheet(
            item = item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(item, action, VideoSheetContext.DEFAULT, list = videos) },
            onDismiss = { sheet = null },
            downloaded = item.id in downloaded,
        )
    }
}

/** The name of a chip. */
@Composable
private fun filterLabel(filter: SearchFilter): String = stringResource(
    when (filter) {
        SearchFilter.ALL -> Res.string.video_filter_all
        SearchFilter.VIDEOS -> Res.string.video_filter_videos
        SearchFilter.SHORTS -> Res.string.video_filter_shorts
        SearchFilter.CHANNELS -> Res.string.video_filter_channels
        SearchFilter.PLAYLISTS -> Res.string.video_filter_playlists
    },
)

/**
 * Whether the desktop's sidebar, with its own search field, stands beside this page: the page is on the desktop's one navigator and
 * the window is wide enough for the sidebar (the desktop page makes the same test, `ShellMetrics.DESKTOP_COMPACT_DP`). The width is
 * read in a derived state, so a window being resized recomposes the page only when the answer changes.
 */
@Composable
private fun sidebarSearchShown(): Boolean {
    val onDesktop = LocalVideoNavigator.current === DesktopVideoState.navigator
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val roomForSidebar by remember(windowInfo, density) {
        derivedStateOf { with(density) { windowInfo.containerSize.width.toDp().value } >= ShellMetrics.DESKTOP_COMPACT_DP }
    }
    return onDesktop && roomForSidebar
}

/** A wide page keeps its list at most 840 dp wide, centred (the column that holds it centres it). */
private fun Modifier.listWidth(wide: Boolean): Modifier = if (wide) widthIn(max = LIST_MAX_WIDTH).fillMaxWidth() else fillMaxWidth()

/** The widest the results list gets on a wide page (spec 5.3). */
private val LIST_MAX_WIDTH = 840.dp

/** Between the field and what stands under it. */
private val FIELD_GAP = 4.dp
