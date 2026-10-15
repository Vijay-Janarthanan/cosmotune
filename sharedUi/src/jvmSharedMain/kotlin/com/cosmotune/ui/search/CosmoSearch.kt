package com.cosmotune.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoChip
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.clear
import com.music.bitchord.sharedui.resources.cosmo_search_top_hits
import com.music.bitchord.sharedui.resources.cosmo_search_use
import com.music.bitchord.sharedui.resources.recent_search_remove
import com.music.bitchord.sharedui.resources.recent_searches
import com.music.bitchord.sharedui.resources.search_empty
import com.music.bitchord.sharedui.resources.search_hint
import com.music.bitchord.sharedui.resources.search_library_hint
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.songListSkeleton
import org.jetbrains.compose.resources.stringResource

/**
 * Search in the new look, with Explore inside it: one page that is, by what the field holds, the idle page (moods, genres and
 * recent searches), the suggestions while typing, or the results. It takes [SearchSwitch]'s parameters, [browse] being Explore's
 * moods and genres that the root hands over; without it the idle page has recent searches only.
 *
 * A fixed column stands above the list: the field (when [showField]; the phone's is in its top bar), the Library switcher while
 * nothing is typed, and the filter chips once there is a result set to narrow. The list under it takes the bottom of
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
    browse: CosmoBrowse? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val reduced = LocalReducedMotion.current

    // The three effects and the two flags that follow are the old screen's (`com.music.bitchord.ui.screens.SearchScreen`), copied
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
    val suggesting = suggestions.isNotEmpty() && !searchingLibrary
    // Live media results arrive from the parallel typeahead pipeline; show them only while the user is still typing.
    val showTypeahead = typeaheadResults.isNotEmpty() && suggesting
    LaunchedEffect(listState, results, loadingMore, searchingLibrary) {
        if (results !is UiState.Success || searchingLibrary) return@LaunchedEffect
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
        val wideWidthDp = if (wide) maxWidth.value else null
        Column(Modifier.fillMaxSize()) {
            // The field, the Library switcher and the filter chips stay fixed at the top, outside the scrolling list, so they are
            // always reachable rather than scrolling away with the results or the recent searches beneath them.
            Column(Modifier.padding(top = topPadding)) {
                if (showField) {
                    CosmoSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmit,
                        placeholder = stringResource(if (searchingLibrary) Res.string.search_library_hint else Res.string.search_hint),
                        modifier = Modifier.padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, bottom = 4.dp),
                        focusRequester = focusRequester,
                    )
                }
                if (sourceSwitcher != null) {
                    AnimatedVisibility(
                        visible = query.isEmpty(),
                        enter = if (reduced) fadeIn(tween(Springs.REDUCED_MS)) else fadeIn() + expandVertically(),
                        exit = if (reduced) fadeOut(tween(Springs.REDUCED_MS)) else fadeOut() + shrinkVertically(),
                    ) {
                        Box(Modifier.padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, bottom = 4.dp)) {
                            sourceSwitcher()
                        }
                    }
                }
                // The filters only mean something once there is a result set to narrow; they stay up for an empty or failed
                // search too, or picking a filter that finds nothing would take away the control needed to leave it. They are
                // YouTube's categories, so the Library source has none.
                if (results != null && !suggesting && !searchingLibrary) {
                    SearchFilterChips(filter = filter, onFilterChange = onFilterChange)
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
                    )
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
                            )
                        }
                    }
                    results == null -> idleRows(
                        browse = browse,
                        lists = lists,
                        wideWidthDp = wideWidthDp,
                        history = history,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
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
                        wide = wide,
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

/**
 * The filters as chips in a row that scrolls sideways, the gutter inside the scroll so it scrolls with them. The chip plays the
 * select haptic itself, so a tap on the chip that is already selected buzzes as well (the old pill did not).
 */
@Composable
private fun SearchFilterChips(filter: SearchFilter, onFilterChange: (SearchFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = CosmoSpacing.margin, top = 6.dp, end = CosmoSpacing.margin, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchFilter.entries.forEach { entry ->
            CosmoChip(label = entry.label, selected = entry == filter, onClick = { onFilterChange(entry) }, haptic = entry != filter)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// idle

/**
 * Nothing searched yet (`V2.search`, `searchIdle`): "Browse by mood" with the moods grid, "Genres" with the genre chips, then the
 * recent searches. Without [browse] there are no moods or genres. When there is nothing at all to show the old sentence stands in.
 */
private fun LazyListScope.idleRows(
    browse: CosmoBrowse?,
    lists: BrowseLists?,
    wideWidthDp: Float?,
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
) {
    val browsing = browse != null && browseBlocks(state = browse.state, lists = lists, onOpen = browse.onOpen, onRetry = browse.onRetry, wideWidthDp = wideWidthDp)
    if (history.isNotEmpty()) {
        recentRows(history, currentSong, isPlaying, onHistoryClick, onHistoryRemove, onHistoryClear)
    } else if (!browsing) {
        item(key = "idle:empty") { MessageState(stringResource(Res.string.search_empty)) }
    }
}

/** What was searched for before: the heading with "Clear", then a row for each, with a button that removes it. */
private fun LazyListScope.recentRows(
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (SearchHistoryEntity) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        CosmoOverline(
            text = stringResource(Res.string.recent_searches),
            trailing = {
                CosmoButton(
                    text = stringResource(Res.string.clear),
                    onClick = onClear,
                    kind = CosmoButtonKind.TEXT,
                    size = CosmoButtonSize.SMALL,
                )
            },
        )
    }
    items(history, key = { "recent:${it.id}" }) { entity ->
        CosmoListRow(
            title = entity.title,
            onClick = { onClick(entity) },
            subtitle = entity.subtitle.ifBlank { typeLabel(entity.entityType.name) },
            leading = CosmoRowLeading.Cover(entity.artworkUrl, circle = entity.entityType == EntityType.ARTIST),
            isCurrent = entity.entityType == EntityType.TRACK && entity.id == currentSong?.videoId,
            isPlaying = isPlaying,
            trailing = {
                CosmoIconButton(
                    icon = CosmoIcons.Close,
                    contentDescription = stringResource(Res.string.recent_search_remove, entity.title),
                    onClick = { onRemove(entity.id) },
                    style = CosmoIconButtonStyle.PLAIN,
                    size = 40.dp,
                )
            },
        )
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
) {
    item(key = "typeahead:title") { CosmoOverline(stringResource(Res.string.cosmo_search_top_hits)) }
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }) { result ->
        when (result) {
            is SearchResult.Track -> TypeaheadSong(result.song, actions, onTypeaheadLongPress)
            is SearchResult.TopTrack -> TypeaheadSong(result.song, actions, onTypeaheadLongPress)
            is SearchResult.Browse -> BrowseListRow(item = result.item, onClick = { actions.onBrowseClick(result.item) }, onLongPress = null)
        }
    }
}

@Composable
private fun TypeaheadSong(song: Song, actions: SearchRowActions, onLongPress: ((Song) -> Unit)?) {
    val current = song.isSameTrackAs(actions.currentSong)
    CosmoSongRow(
        song = song,
        onClick = { actions.onTopResultPlay(song) },
        onLongPress = onLongPress?.let { held -> { held(song) } },
        isCurrent = current,
        isPlaying = actions.isPlaying && current,
    )
}
