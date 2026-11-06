package com.cosmotune.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.SearchHighlight
import com.cosmotune.design.Springs
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitSongRowFor
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitRecordGrid
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.data.model.BrowseItem
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchHistoryEntity
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_search_use
import com.cosmotune.music.sharedui.resources.recent_search_remove
import com.cosmotune.music.sharedui.resources.search_empty
import com.cosmotune.music.sharedui.resources.search_hint
import com.cosmotune.music.sharedui.resources.search_library_hint
import com.cosmotune.music.sharedui.resources.search_addons_hint
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.songListSkeleton
import org.jetbrains.compose.resources.stringResource

/**
 * Search in Orbit, with Explore inside it (Canvas repo, design/cosmotune/pages-orbit/board.html, section 3): one page that is,
 * by what the field holds, the idle page (recent searches as chips, then Explore's moods and genres as colour tiles), the
 * suggestions while typing, or the results. It takes [SearchSwitch]'s parameters, [browse] being Explore's moods and genres that
 * the root hands over; without it the idle page has recent searches only.
 *
 * A fixed column stands above the list: the big search pill (when [showField]), the source switcher while nothing is typed, the
 * "Search in" chips, and the filter chips once there is a result set to narrow. The list under it takes the bottom of
 * [contentPadding].
 */
@Composable
fun CosmoSearch(
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
    searchIn: com.cosmotune.ui.search.SearchInState? = null,
    browse: CosmoBrowse? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val reduced = LocalReducedMotion.current
    val fonts = orbitFonts()
    val look = orbitLook()

    // The three effects and the two flags that follow are the old screen's (`com.cosmotune.music.ui.screens.SearchScreen`), copied
    // as they are: what they do is the page's behaviour, and the new look only changes how it is drawn.
    // Tapping the search tab from the nav bar sets focusRequested;
    // respond by focusing the field and opening the keyboard.
    LaunchedEffect(focusRequested) {
        if (focusRequested && showField) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onFocusHandled()
        }
    }
    // Search keeps one list state while its contents change. Reset it for each
    // new request so choosing a recent search cannot inherit the history's
    // previous scroll position (or a previous result page's position).
    LaunchedEffect(scrollResetTrigger) {
        if (scrollResetTrigger > 0) listState.scrollToItem(0)
    }
    // A non-empty suggestion list means the field is mid-edit: nothing below it is worth showing while it is up, the results are
    // for whatever was searched before this edit began, and so are the filter chips above them.
    val suggesting = suggestions.isNotEmpty() && !searchingLibrary && !searchingAddons
    // Live media results arrive from the parallel typeahead pipeline; show them only while the user is still typing.
    val showTypeahead = typeaheadResults.isNotEmpty() && suggesting
    LaunchedEffect(listState, results, loadingMore, searchingLibrary, searchingAddons) {
        if (results !is UiState.Success || searchingLibrary || searchingAddons) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (!loadingMore && total > 0 && lastVisible >= total - 4) onLoadMore()
        }
    }

    val lists = rememberBrowseLists(browse?.state)
    val actions = SearchRowActions(
        currentSong = currentSong,
        isPlaying = isPlaying,
        onSongClick = onSongClick,
        onSongLongPress = onSongLongPress,
        onSongSwipe = onSongSwipe,
        onTopResultPlay = onTopResultPlay,
        onTopResultPlaylist = onTopResultPlaylist,
        onBrowseClick = onBrowseClick,
        onBrowseLongPress = onBrowseLongPress,
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth.value >= WIDE_SEARCH_DP
        val side = if (wide) 28.dp else 16.dp
        val (columns, cell) = orbitRecordGrid(maxWidth, side)
        val page = SearchPage(fonts, look, side, wide, columns, cell)
        val tiles = tileColumns(maxWidth.value, side.value)
        Column(Modifier.fillMaxSize()) {
            // The pill, the source switcher and the filter chips stay fixed at the top, outside the scrolling list, so they are
            // always reachable rather than scrolling away with the results or the recent searches beneath them.
            Column(Modifier.padding(top = topPadding)) {
                if (showField) {
                    OrbitSearchPill(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmit,
                        placeholder = stringResource(
                            when {
                                searchingLibrary -> Res.string.search_library_hint
                                searchingAddons -> Res.string.search_addons_hint
                                else -> Res.string.search_hint
                            },
                        ),
                        fonts = fonts,
                        look = look,
                        focusRequester = focusRequester,
                        modifier = Modifier.padding(start = side, end = side, top = 14.dp, bottom = 10.dp),
                    )
                }
                if (sourceSwitcher != null) {
                    AnimatedVisibility(
                        visible = query.isEmpty(),
                        enter = if (reduced) fadeIn(tween(Springs.REDUCED_MS)) else fadeIn() + expandVertically(),
                        exit = if (reduced) fadeOut(tween(Springs.REDUCED_MS)) else fadeOut() + shrinkVertically(),
                    ) {
                        Box(Modifier.padding(start = side, end = side, bottom = 4.dp)) {
                            sourceSwitcher()
                        }
                    }
                }
                // "Search in": every installed source by name, the remembered choice selected. Up while typing too, so where the
                // query goes is chosen before it is sent.
                if (searchIn != null && !searchingLibrary) SearchInChips(searchIn, cosmo = true)
                // The filters only mean something once there is a result set to narrow; they stay up for an empty or failed
                // search too, or picking a filter that finds nothing would take away the control needed to leave it. They are
                // YouTube's categories, so the Library source has none.
                if (results != null && !suggesting && !searchingLibrary && !searchingAddons) {
                    SearchFilterChips(filter = filter, onFilterChange = onFilterChange, fonts = fonts, look = look, side = side)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                when {
                    searchingLibrary -> libraryRows(
                        query = query.trim(),
                        results = libraryResults,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onSongClick = onLibrarySongClick,
                        onSongLongPress = onSongLongPress,
                        onSongSwipe = onSongSwipe,
                        page = page,
                    )
                    searchingAddons -> if (addonResults == null && query.isBlank() && addonHistory.isNotEmpty()) {
                        addonRecentChips(addonHistory, onAddonHistoryClick, onAddonHistoryRemove, onAddonHistoryClear, page)
                    } else if (searchIn?.progress != null) {
                        // Source by source, each with its own state, as the answers come in.
                        addonSourceSections(searchIn.progress, searchIn, "addon", cosmo = true) { tracks, index, song ->
                            val current = playingRowMatcher(tracks, currentSong)(song)
                            OrbitSongRowFor(
                                song = song,
                                fonts = fonts,
                                look = look,
                                onClick = { onAddonSongClick(tracks, index) },
                                modifier = Modifier.padding(horizontal = side, vertical = 4.dp),
                                onLongPress = { onSongLongPress(song) },
                                onSwipe = { onSongSwipe(song) },
                                now = current,
                                playing = isPlaying && current,
                                showSourceQuality = true,
                            )
                        }
                    } else {
                        libraryRows(
                            query = query.trim(),
                            results = addonResults,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            onSongClick = onAddonSongClick,
                            onSongLongPress = onSongLongPress,
                            onSongSwipe = onSongSwipe,
                            page = page,
                            addons = true,
                        )
                    }
                    suggesting -> {
                        suggestionRows(
                            query = query,
                            suggestions = suggestions,
                            // Picking one is done typing, so the keyboard comes down with it and the results get the whole screen.
                            onClick = { term ->
                                onSuggestionClick(term)
                                focusManager.clearFocus()
                            },
                            onFill = onQueryChange,
                        )
                        if (showTypeahead) {
                            typeaheadRows(
                                typeaheadResults = typeaheadResults,
                                actions = actions,
                                onTypeaheadLongPress = onTypeaheadLongPress,
                                page = page,
                            )
                        }
                    }
                    results == null -> idleRows(
                        browse = browse,
                        lists = lists,
                        page = page,
                        columns = tiles,
                        history = history,
                        onHistoryClick = onHistoryClick,
                        onHistoryRemove = onHistoryRemove,
                        onHistoryClear = onHistoryClear,
                    )
                    results is UiState.Loading -> songListSkeleton(circular = filter == SearchFilter.ARTISTS)
                    results is UiState.Error -> item(key = "search:error") { MessageState(results.message) }
                    results is UiState.Success -> resultRows(
                        rows = results.data,
                        query = query,
                        filter = filter,
                        page = page,
                        loadingMore = loadingMore,
                        actions = actions,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// the filters

/** The order of the filter chips: the board's (All, Songs, Albums, Artists, Playlists, Videos); a filter added later goes last. */
private val FILTER_ORDER = listOf(SearchFilter.ALL, SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS, SearchFilter.VIDEOS)

/**
 * The filters as Orbit chips in a row that scrolls sideways, every filter the search offers. The chip plays the select haptic
 * itself, so only a tap that changes the filter buzzes.
 */
@Composable
private fun SearchFilterChips(filter: SearchFilter, onFilterChange: (SearchFilter) -> Unit, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    val entries = remember { SearchFilter.entries.sortedBy { FILTER_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } } }
    OrbitChipRow(side, Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        entries.forEach { entry ->
            OrbitSelectChip(entry.label, selected = entry == filter, fonts = fonts, look = look, onClick = { onFilterChange(entry) }, haptic = entry != filter)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// idle

/**
 * Nothing searched yet: the recent searches as chips, then Explore's moods and genres as colour tiles. Without [browse] there are
 * no tiles. When there is nothing at all to show the old sentence stands in.
 */
private fun LazyListScope.idleRows(
    browse: CosmoBrowse?,
    lists: BrowseLists?,
    page: SearchPage,
    columns: Int,
    history: List<SearchHistoryEntity>,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
) {
    if (history.isNotEmpty()) recentChips(history, onHistoryClick, onHistoryRemove, onHistoryClear, page)
    val browsing = browse != null &&
        browseTiles(state = browse.state, lists = lists, onOpen = browse.onOpen, onRetry = browse.onRetry, columns = columns, side = page.side, fonts = page.fonts, look = page.look, together = true)
    if (history.isEmpty() && !browsing) {
        item(key = "idle:empty") { MessageState(stringResource(Res.string.search_empty)) }
    }
}

/** The mono "Recent" label with Clear at its end. */
private fun LazyListScope.recentHeader(key: String, onClear: () -> Unit, page: SearchPage) {
    item(key = key) {
        Row(Modifier.fillMaxWidth().padding(start = page.side + 6.dp, end = page.side, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OrbitLabel("Recent", page.fonts, page.look.dim, Modifier.weight(1f))
            Text("Clear", style = page.fonts.chip.copy(fontSize = 13.sp), color = page.look.ink,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClear).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** What was searched for before, as chips: the cover as a small disc (a clock for words alone), the name, and a cross that removes it. */
private fun LazyListScope.recentChips(
    history: List<SearchHistoryEntity>,
    onClick: (SearchHistoryEntity) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    page: SearchPage,
) {
    recentHeader("recent:header", onClear, page)
    item(key = "recent:chips") {
        OrbitChipRow(page.side, Modifier.padding(top = 6.dp)) {
            history.forEach { entity ->
                key("recent:${entity.id}") {
                    RecentChip(entity.title, entity.artworkUrl, page, onClick = { onClick(entity) }, onRemove = { onRemove(entity.id) })
                }
            }
        }
    }
}

/** The Add-ons source's recents: the words searched before (no entity behind them), each searched again by a tap. */
private fun LazyListScope.addonRecentChips(
    history: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    page: SearchPage,
) {
    recentHeader("addon-recent:header", onClear, page)
    item(key = "addon-recent:chips") {
        OrbitChipRow(page.side, Modifier.padding(top = 6.dp)) {
            history.forEach { term ->
                key("addon-recent:$term") { RecentChip(term, null, page, onClick = { onClick(term) }, onRemove = { onRemove(term) }) }
            }
        }
    }
}

@Composable
private fun RecentChip(title: String, artwork: String?, page: SearchPage, onClick: () -> Unit, onRemove: () -> Unit) {
    val look = page.look
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(look.chip).clickable(onClick = onClick).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (artwork != null) Disc(artwork, 24.dp) else Icon(CosmoIcons.Clock, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(title, style = page.fonts.chip, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        Box(
            Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(CosmoIcons.Close, contentDescription = stringResource(Res.string.recent_search_remove, title), tint = look.dim, modifier = Modifier.size(14.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// typing

/**
 * What YouTube would complete the half-typed query to, in place of the results (`searchTyping`). The first suggestion is the text
 * as typed, put there by the view model, and is skipped: it is already in the field. At most three follow.
 */
private fun LazyListScope.suggestionRows(
    query: String,
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    // A list-level inset, not padding inside the first row, so the gap under the field is stable whatever that row shows.
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    itemsIndexed(suggestions.drop(1).take(3), key = { index, term -> "suggest:$index:$term" }) { _, term ->
        SuggestionRow(term = term, typed = query, onClick = { onClick(term) }, onFill = { onFill(term) })
    }
}

/**
 * One suggestion: a magnifier, the text with the part that was typed in weight 700, and an arrow that puts the suggestion in the
 * field to carry on typing from it. Tapping the text searches it.
 */
@Composable
private fun SuggestionRow(term: String, typed: String, onClick: () -> Unit, onFill: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val text = remember(term, typed) {
        val (bold, rest) = SearchHighlight.split(term, typed)
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            append(rest)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            // The arrow's 48 dp target ends 4 dp from the edge, so its glyph stands near the page margin.
            .padding(start = CosmoSpacing.margin, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CosmoIcons.Search, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = type.forText(
                TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 15.5.sp * 1.3f),
                term,
            ),
            color = Color(palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CosmoIconButton(
            icon = FillArrow,
            contentDescription = stringResource(Res.string.cosmo_search_use, term),
            onClick = onFill,
            style = CosmoIconButtonStyle.PLAIN,
            size = 40.dp,
        )
    }
}

/** The arrow of "use this suggestion": `CosmoIcons.ArrowLeft` turned 45 degrees, so it points up and to the left, at the field. */
private val FillArrow: ImageVector by lazy {
    val arrow = CosmoIcons.ArrowLeft
    ImageVector.Builder(
        name = "arrow-up-left",
        defaultWidth = arrow.defaultWidth,
        defaultHeight = arrow.defaultHeight,
        viewportWidth = arrow.viewportWidth,
        viewportHeight = arrow.viewportHeight,
    ).apply {
        addGroup(name = "turn", rotate = 45f, pivotX = arrow.viewportWidth / 2f, pivotY = arrow.viewportHeight / 2f)
        for (node in arrow.root) {
            if (node !is VectorPath) continue
            addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                fill = node.fill,
                stroke = node.stroke,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter,
            )
        }
        clearGroup()
    }.build()
}


/**
 * Live media hits under the suggestions, while the user is still typing, as "Top hits": songs play on a tap and open the song
 * menu on a hold, the rest open their page.
 */
private fun LazyListScope.typeaheadRows(
    typeaheadResults: List<SearchResult>,
    actions: SearchRowActions,
    onTypeaheadLongPress: ((Song) -> Unit)?,
    page: SearchPage,
) {
    item(key = "typeahead:title") { OrbitLabel("Top hits", page.fonts, page.look.dim, Modifier.padding(start = page.side + 6.dp, top = 18.dp, bottom = 8.dp)) }
    val isCurrentRow = playingRowMatcher(
        typeaheadResults.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song },
        actions.currentSong,
    )
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }) { result ->
        val m = Modifier.padding(horizontal = page.side, vertical = 4.dp)
        when (result) {
            is SearchResult.Track -> TypeaheadSong(result.song, actions, onTypeaheadLongPress, isCurrentRow(result.song), page, m)
            is SearchResult.TopTrack -> TypeaheadSong(result.song, actions, onTypeaheadLongPress, isCurrentRow(result.song), page, m)
            is SearchResult.Browse -> BrowseListRow(item = result.item, onClick = { actions.onBrowseClick(result.item) }, onLongPress = null, page = page, modifier = m)
        }
    }
}

@Composable
private fun TypeaheadSong(song: Song, actions: SearchRowActions, onLongPress: ((Song) -> Unit)?, current: Boolean, page: SearchPage, modifier: Modifier) {
    OrbitSongRowFor(
        song = song,
        fonts = page.fonts,
        look = page.look,
        onClick = { actions.onTopResultPlay(song) },
        modifier = modifier,
        onLongPress = onLongPress?.let { held -> { held(song) } },
        now = current,
        playing = actions.isPlaying && current,
    )
}
