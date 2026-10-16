package com.cosmotune.video.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_error_bot
import com.cosmotune.video.resources.video_error_network
import com.cosmotune.video.resources.video_error_parsing
import com.cosmotune.video.resources.video_list_error
import com.cosmotune.video.resources.video_no_results
import com.cosmotune.video.resources.video_no_results_hint
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.video.ui.blocks.ChannelRow
import com.cosmotune.video.ui.blocks.PlaylistRow
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.VideoRow
import com.cosmotune.video.ui.blocks.rowThumbnailSize
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The results of a search (spec 5.3): the pages the source answers one at a time, kept so that a Back from a video finds them where
 * they were, and the rows they are drawn as. The page around them is VideoSearch; the typing page is SearchSuggestions.
 */

/** How many rows from the end of the list the next page is asked for. */
internal const val LOAD_MORE_AHEAD = 4

/** The skeleton rows of a first page that is loading, and of a next page that is. */
private const val SKELETON_ROWS = 8
private const val MORE_SKELETON_ROWS = 3

/** Where a tab is: never asked, waiting for its first page, holding its rows, or failed on its first page. */
internal enum class SearchPhase { NOT_STARTED, LOADING, READY, FAILED }

/**
 * The results of one search with one filter: the rows so far, the token of the next page, and whether a page is on its way.
 * Its state is snapshot state, so the lazy list that draws it follows it; its work runs in the scope the page hands each call, and
 * a page that leaves in the middle of a load leaves the tab as it was before that load (the next visit asks again).
 *
 * A failed first page is [SearchPhase.FAILED] with [error]; a failed next page leaves the rows and sets [moreFailed], which only
 * [retry] clears (scrolling does not ask again). A page that adds nothing new, or that points at the token it was asked with,
 * ends the list.
 */
@Stable
internal class SearchTab(
    val source: VideoSource,
    val query: String,
    val filter: SearchFilter,
    val createdMs: Long,
) {
    var phase: SearchPhase by mutableStateOf(SearchPhase.NOT_STARTED)
        private set
    var items: List<ListItem> by mutableStateOf(emptyList())
        private set
    var next: PageToken? by mutableStateOf(null)
        private set
    var loadingMore: Boolean by mutableStateOf(false)
        private set
    var moreFailed: Boolean by mutableStateOf(false)
        private set
    var error: Throwable? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Each first page gets a number; an answer that arrives for an older one is let go. */
    private var generation = 0

    /** Loads the first page, unless the tab has it (or is on its way to it); a tab that failed last time tries again. */
    fun start(scope: CoroutineScope) {
        if (phase == SearchPhase.NOT_STARTED || phase == SearchPhase.FAILED) loadFirst(scope)
    }

    /** Asks for the next page, if there is one and nothing is on its way and the last ask did not fail. */
    fun loadMore(scope: CoroutineScope) {
        if (!moreFailed) fetchMore(scope)
    }

    /** "Try again": the first page after a failure, the next page after a failure of that. */
    fun retry(scope: CoroutineScope) {
        when {
            phase == SearchPhase.FAILED -> loadFirst(scope)
            phase == SearchPhase.READY && moreFailed -> fetchMore(scope)
        }
    }

    private fun loadFirst(scope: CoroutineScope) {
        val mine = ++generation
        job?.cancel()
        phase = SearchPhase.LOADING
        error = null
        moreFailed = false
        loadingMore = false
        job = scope.launch {
            try {
                val page = source.search(query, filter, null)
                if (mine != generation) return@launch
                items = mergeUnique(emptyList(), page.items)
                next = page.next
                phase = SearchPhase.READY
            } catch (e: CancellationException) {
                if (mine == generation && phase == SearchPhase.LOADING) phase = SearchPhase.NOT_STARTED
                throw e
            } catch (e: Exception) {
                if (mine == generation) {
                    error = e
                    phase = SearchPhase.FAILED
                }
            }
        }
    }

    private fun fetchMore(scope: CoroutineScope) {
        val token = next ?: return
        if (phase != SearchPhase.READY || loadingMore) return
        val mine = generation
        loadingMore = true
        moreFailed = false
        job = scope.launch {
            try {
                val page = source.search(query, filter, token)
                if (mine != generation) return@launch
                items = mergeUnique(items, page.items)
                next = page.next?.takeIf { it != token }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) moreFailed = true
            } finally {
                if (mine == generation) loadingMore = false
            }
        }
    }
}

/**
 * The tabs the process holds (spec 5.3 and the back stack: leaving the results for a video and coming Back finds them again): the
 * latest [MAX_TABS], each for [MAX_AGE_MS]. One is found by the source it asks, its query (trimmed, otherwise as typed) and its filter.
 */
internal object SearchSessions {
    const val MAX_TABS = 12
    const val MAX_AGE_MS = 10 * 60_000L

    private val tabs = object : LinkedHashMap<String, SearchTab>(MAX_TABS * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchTab>?): Boolean = size > MAX_TABS
    }

    /** How many tabs are held. */
    val size: Int
        @Synchronized get() = tabs.size

    /** The tab for [query] with [filter] on [source]: the one held, or a new one when there is none or it is older than [MAX_AGE_MS]. */
    @Synchronized
    fun tab(source: VideoSource, query: String, filter: SearchFilter, nowMs: Long = System.currentTimeMillis()): SearchTab {
        val trimmed = query.trim()
        val key = filter.name + "\u0000" + trimmed
        val held = tabs[key]
        if (held != null && held.source === source && nowMs - held.createdMs <= MAX_AGE_MS) return held
        return SearchTab(source, trimmed, filter, nowMs).also { tabs[key] = it }
    }

    /** Forgets every tab. */
    @Synchronized
    fun clear() {
        tabs.clear()
    }
}

/** What tells one result from another in a lazy list: the kind and the id (a channel and a playlist may share an id). */
internal fun ListItem.searchKey(): String = when (this) {
    is ListItem.Video -> "v:${item.id}"
    is ListItem.Channel -> "c:${item.id}"
    is ListItem.Playlist -> "p:${item.id}"
}

/** [old] followed by what [added] holds that [old] does not (and each of those once); [old] itself when there is nothing new. */
internal fun mergeUnique(old: List<ListItem>, added: List<ListItem>): List<ListItem> {
    val seen = HashSet<String>(old.size + added.size)
    for (entry in old) seen += entry.searchKey()
    val fresh = added.filter { seen.add(it.searchKey()) }
    return when {
        fresh.isEmpty() -> old
        old.isEmpty() -> fresh
        else -> old + fresh
    }
}

/** The rows the page shows: [items] without their Shorts while "Hide Shorts" is on (channels and playlists stay). */
internal fun visibleResults(items: List<ListItem>, hideShorts: Boolean): List<ListItem> =
    ShortsFilter.filter(items, hideShorts) { (it as? ListItem.Video)?.item }

/** The words under a failed search: no connection, a changed source, the bot check, else the list's own. */
internal fun searchErrorMessage(error: Throwable?): StringResource = when (error) {
    is VideoSourceError.Network -> Res.string.video_error_network
    is VideoSourceError.Parsing -> Res.string.video_error_parsing
    is VideoSourceError.BotCheck -> Res.string.video_error_bot
    else -> Res.string.video_list_error
}

/** What a row of the results does with a tap on it. */
@Immutable
internal class SearchRowCallbacks(
    val onVideo: (VideoItem) -> Unit,
    val onMore: (VideoItem) -> Unit,
    val onChannel: (ChannelItem) -> Unit,
    val onSubscribe: (ChannelItem) -> Unit,
    val onPlaylist: (PlaylistItem) -> Unit,
)

// ---------------------------------------------------------------------------------------------------------------------------
// the list

/**
 * The results of [tab] as one lazy list: skeleton rows while the first page loads, the empty state with Try again when it failed,
 * "No results" when there is nothing to show and nothing more to ask for, else a row for each of [visible] (a video row, a channel
 * row with its Subscribe button, a playlist row) with the next page's skeleton rows or a Try again at the end. The next page is asked
 * for when the list is scrolled to within [LOAD_MORE_AHEAD] rows of its end, and at once when the filter has taken out everything
 * so far. [bottomPadding] is what the page keeps clear for the floating chrome; [wide] gives the rows the desktop's thumbnail.
 */
@Composable
internal fun ResultsList(
    tab: SearchTab,
    visible: List<ListItem>,
    wide: Boolean,
    callbacks: SearchRowCallbacks,
    subscribed: Set<String>,
    downloaded: Set<String>,
    bottomPadding: Dp,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, tab) {
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - LOAD_MORE_AHEAD) tab.loadMore(scope)
        }
    }
    val nothingShown = visible.isEmpty()
    LaunchedEffect(tab, nothingShown, tab.items.size, tab.next) {
        if (nothingShown) tab.loadMore(scope)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        when {
            tab.phase == SearchPhase.FAILED -> item(key = "error") {
                CosmoEmptyState(
                    message = stringResource(searchErrorMessage(tab.error)),
                    actionLabel = stringResource(Res.string.video_retry),
                    onAction = { tab.retry(scope) },
                )
            }
            tab.phase != SearchPhase.READY -> items(SKELETON_ROWS, key = { "skeleton:$it" }) { ResultSkeletonRow(wide) }
            nothingShown && tab.next == null -> item(key = "empty") { NoResults(tab.query) }
            else -> {
                items(visible, key = { it.searchKey() }) { entry -> ResultRow(entry, wide, callbacks, subscribed, downloaded) }
                when {
                    tab.moreFailed -> item(key = "more:retry") {
                        Box(Modifier.fillMaxWidth().padding(vertical = CosmoSpacing.margin), contentAlignment = Alignment.Center) {
                            CosmoButton(
                                text = stringResource(Res.string.video_retry),
                                onClick = { tab.retry(scope) },
                                kind = CosmoButtonKind.TONAL,
                                size = CosmoButtonSize.SMALL,
                            )
                        }
                    }
                    tab.loadingMore || (nothingShown && tab.next != null) ->
                        items(MORE_SKELETON_ROWS, key = { "skeleton:more:$it" }) { ResultSkeletonRow(wide) }
                }
            }
        }
    }
}

/** One result: its block. */
@Composable
private fun ResultRow(entry: ListItem, wide: Boolean, callbacks: SearchRowCallbacks, subscribed: Set<String>, downloaded: Set<String>) {
    when (entry) {
        is ListItem.Video -> SearchVideoRow(entry.item, wide, entry.item.id in downloaded, callbacks)
        is ListItem.Channel -> ChannelRow(
            item = entry.item,
            subscribed = entry.item.id in subscribed,
            onClick = { callbacks.onChannel(entry.item) },
            onSubscribe = { callbacks.onSubscribe(entry.item) },
        )
        is ListItem.Playlist -> PlaylistRow(item = entry.item, onClick = { callbacks.onPlaylist(entry.item) }, desktop = wide)
    }
}

/** A video row with the title and the picture DeArrow has for it (its lookups are Y-16's, limited and batched). */
@Composable
private fun SearchVideoRow(item: VideoItem, wide: Boolean, downloaded: Boolean, callbacks: SearchRowCallbacks) {
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val shown = remember(item, picture) { if (picture == item.thumbnailUrl) item else item.copy(thumbnailUrl = picture) }
    VideoRow(
        item = shown,
        onClick = { callbacks.onVideo(item) },
        onMore = { callbacks.onMore(item) },
        desktop = wide,
        title = title,
        downloaded = downloaded,
    )
}

/** The page of a search that found nothing: the empty state with the query in curly quotes and the hint. */
@Composable
private fun NoResults(query: String) {
    CosmoEmptyState(
        message = stringResource(Res.string.video_no_results_hint),
        title = stringResource(Res.string.video_no_results, "“$query”"),
        icon = CosmoIcons.Search,
    )
}

/** The shape of a [VideoRow] while it loads: the thumbnail, a title line and a shorter meta line, in the shimmer. */
@Composable
private fun ResultSkeletonRow(wide: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = VideoMetrics.ROW_PAD_V_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoShimmer(Modifier.rowThumbnailSize(wide), ThumbnailShape)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SKELETON_LINE_GAP)) {
            CosmoShimmer(Modifier.fillMaxWidth().height(SKELETON_TITLE_HEIGHT))
            CosmoShimmer(Modifier.fillMaxWidth(SKELETON_META_FRACTION).height(SKELETON_META_HEIGHT))
        }
    }
}

private val SKELETON_LINE_GAP = 8.dp
private val SKELETON_TITLE_HEIGHT = 14.dp
private val SKELETON_META_HEIGHT = 12.dp
private const val SKELETON_META_FRACTION = 0.55f
